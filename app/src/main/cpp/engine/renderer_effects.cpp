// User effects of a layer, done on the GPU inside the C++ engine (same code for the preview and the export):
//   Gaussian blur (true Gaussian, separable, linear-sampling taps, pyramid for big radii)
//   Glow (bright pass with soft knee + Karis average, 13-tap down chain, tent-filter up chain, screen composite)
//   RGB split (spectral: 9 taps across the colour range, linear or radial like a lens)
//   Film grain (gaussian-ish, luminance weighted, optional colour, 24 patterns per second)
//   Vignette (aspect-correct, soft, dithered)
//   Chroma key (v135): keys by the distance in the CbCr plane to the key colour, soft edge, spill removal
//   v140 (fx_shaders.h, 4 shared programs): directional / radial blur, sharpen; exposure, brightness & contrast, hue / saturation,
//   levels, colour balance, tint, posterize; wave warp, twirl, bulge, mirror, kaleidoscope, turbulent displace; mosaic,
//   halftone, scanlines, glitch. Every one is one full-screen pass (fxShaded) and is skipped when its sliders are neutral.
//   Masks (v134): one pass per mask into a one-channel coverage picture (shape distance field + feather), then one
//   pass that multiplies the layer by the coverage. The maths is mask_plan.cpp (host-tested), the GLSL mirrors it.
// All of them work on the premultiplied picture the layer shader uses (top of the picture at v = 1).
// The small pictures of the pyramids are RGBA16F when the phone can render to it, else RGBA8.
#include <GLES3/gl3.h>
#include <android/log.h>

#include <algorithm>
#include <cmath>
#include <cstring>

#include "fx_shaders.h"
#include "renderer.h"

#define TAG "AmRendererEffects"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace am {
namespace {

enum EffId { EF_DOWN = 0, EF_BLUR, EF_COPY, EF_GLOW_UP, EF_GLOW_COMP, EF_SPLIT, EF_GRAIN, EF_VIG, EF_MASK, EF_MASK_APPLY, EF_CHROMA,
             EF_BLUR2, EF_COLOR, EF_WARP, EF_STYLE, EF_COPYBG, EF_GLOW_SCAN,
             EF_LUMA_KEY, EF_FOUR_GRADIENT, EF_TURBULENT_AM, EF_PIXELATE_AM,
             EF_CHROMA_KEY_AM, EF_SOFT_GLOW, EF_FILL_BEHIND,
             EF_DISPLACE_MAP, EF_SMOOTH_BEVEL, EF_LIGHT_GLOW, EF_INNER_GLOW,
             EF_DARK_GLOW_EXTRACT, EF_DARK_GLOW_COMP, EF_EXTRUDE,
             EF_EDGE_GLOW_EDGE, EF_EDGE_GLOW_COMP,
             EF_CHANNEL_MAP, EF_MOSAIC_AM, EF_BOX_PAIR, EF_GLOW_OUTER, EF_ELECTRIC_AM,
             EF_SMOOTH_EDGES_AM, EF_DEEP_GLOW_COMP, EF_DROP_SHADOW,
             EF_BBMAKER, EF_UNSHARP_COMP, EF_NEWSHARPEN_COMP, EF_AM_BLENDGEN,
             EF_DEEP_GLOW1_COMP, EF_SPILL_STREAK, EF_SPILL_OVER, EF_SPILL_FINAL, EF_COUNT };  // eff_[] in renderer.h has room for 52

const char* kVert = R"(#version 300 es
layout(location = 0) in vec2 aPos;
out vec2 vUv;
void main() {
    gl_Position = vec4(aPos * 2.0, 0.0, 1.0);
    vUv = aPos + 0.5;
}
)";

// Shared by the shaders that write 8-bit pictures: removes banding of soft gradients.
#define AM_DITHER                                                                     \
    "float amHash(vec2 p) {\n"                                                        \
    "    vec3 p3 = fract(vec3(p.xyx) * 0.1031);\n"                                    \
    "    p3 += dot(p3, p3.yzx + 33.33);\n"                                            \
    "    return fract((p3.x + p3.y) * p3.z);\n"                                       \
    "}\n"                                                                             \
    "float amDither() {\n"                                                            \
    "    return (amHash(gl_FragCoord.xy) + amHash(gl_FragCoord.xy + 17.17) - 1.0) / 255.0;\n" \
    "}\n"

// 13 taps (Call of Duty: Advanced Warfare). uPre = 1: bright pass for the glow (soft knee on the brightest
// channel, Karis average so a single hot pixel does not flicker).
const char* kDown = "#version 300 es\n"
R"(precision highp float;
uniform sampler2D uTex;
uniform vec2 uTexel;
uniform int uPre;
uniform vec2 uPreP;
in vec2 vUv;
out vec4 oColor;
vec4 s(float x, float y) { return texture(uTex, vUv + vec2(x, y) * uTexel); }
vec3 pre(vec3 c) {
    float br = max(c.r, max(c.g, c.b));
    float soft = clamp(br - uPreP.x + uPreP.y, 0.0, 2.0 * uPreP.y);
    soft = soft * soft / (4.0 * uPreP.y + 0.0001);
    float contrib = max(soft, br - uPreP.x) / max(br, 0.0001);
    return c * max(contrib, 0.0);
}
float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
void main() {
    vec4 a = s(-2.0, -2.0), b = s(0.0, -2.0), c = s(2.0, -2.0);
    vec4 d = s(-2.0, 0.0), e = s(0.0, 0.0), f = s(2.0, 0.0);
    vec4 g = s(-2.0, 2.0), h = s(0.0, 2.0), i = s(2.0, 2.0);
    vec4 j = s(-1.0, -1.0), k = s(1.0, -1.0), l = s(-1.0, 1.0), m = s(1.0, 1.0);
    if (uPre == 0) {
        oColor = e * 0.125 + (a + c + g + i) * 0.03125 + (b + d + f + h) * 0.0625 + (j + k + l + m) * 0.125;
    } else {
        vec3 A = pre(a.rgb), B = pre(b.rgb), C = pre(c.rgb), D = pre(d.rgb), E = pre(e.rgb);
        vec3 F = pre(f.rgb), G = pre(g.rgb), H = pre(h.rgb), I = pre(i.rgb);
        vec3 J = pre(j.rgb), K = pre(k.rgb), L = pre(l.rgb), M = pre(m.rgb);
        vec3 g0 = (A + B + D + E) * 0.25;
        vec3 g1 = (B + C + E + F) * 0.25;
        vec3 g2 = (D + E + G + H) * 0.25;
        vec3 g3 = (E + F + H + I) * 0.25;
        vec3 g4 = (J + K + L + M) * 0.25;
        float w0 = 0.125 / (1.0 + luma(g0));
        float w1 = 0.125 / (1.0 + luma(g1));
        float w2 = 0.125 / (1.0 + luma(g2));
        float w3 = 0.125 / (1.0 + luma(g3));
        float w4 = 0.5 / (1.0 + luma(g4));
        vec3 rgb = (g0 * w0 + g1 * w1 + g2 * w2 + g3 * w3 + g4 * w4) / (w0 + w1 + w2 + w3 + w4);
        oColor = vec4(rgb, 1.0);
    }
}
)";

// Gaussian along uDir (one texel step), taps = centre + pairs fetched between two texels (see fx_plan.cpp).
const char* kBlur = R"(#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform vec2 uDir;
uniform float uCentre;
uniform int uN;
uniform float uOff[20];
uniform float uWt[20];
in vec2 vUv;
out vec4 oColor;
void main() {
    vec4 sum = texture(uTex, vUv) * uCentre;
    for (int i = 0; i < 20; i++) {
        if (i >= uN) break;
        vec2 o = uDir * uOff[i];
        sum += (texture(uTex, vUv + o) + texture(uTex, vUv - o)) * uWt[i];
    }
    oColor = sum;
}
)";

const char* kCopy = R"(#version 300 es
precision highp float;
uniform sampler2D uTex;
in vec2 vUv;
out vec4 oColor;
void main() { oColor = texture(uTex, vUv); }
)";

// Copy Background: the picture behind the layer (the frame so far) replaces the layer's colour, cut to the layer's shape.
// uRect = the part of the canvas this picture covers (x, y from the bottom, w, h as fractions of the canvas).
const char* kCopyBg = R"(#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform sampler2D uBg;
uniform vec4 uRect;
uniform float uAmount;
in vec2 vUv;
out vec4 oColor;
void main() {
    vec4 s = texture(uTex, vUv);
    vec3 bg = texture(uBg, uRect.xy + vUv * uRect.zw).rgb;  // the frame is opaque, so premultiplied == plain
    oColor = mix(s, vec4(bg * s.a, s.a), uAmount);
}
)";

// Coarse level -> fine level: 3x3 tent filter on the coarse picture, mixed into the fine one.
const char* kGlowUp = R"(#version 300 es
precision highp float;
uniform sampler2D uFine;
uniform sampler2D uCoarse;
uniform vec2 uCoarseTexel;
uniform float uSpread;
in vec2 vUv;
out vec4 oColor;
vec3 c(float x, float y) { return texture(uCoarse, vUv + vec2(x, y) * uCoarseTexel).rgb; }
void main() {
    vec3 up = c(0.0, 0.0) * 4.0
            + (c(-1.0, 0.0) + c(1.0, 0.0) + c(0.0, -1.0) + c(0.0, 1.0)) * 2.0
            + (c(-1.0, -1.0) + c(1.0, -1.0) + c(-1.0, 1.0) + c(1.0, 1.0));
    up *= 0.0625;
    vec3 fine = texture(uFine, vUv).rgb;
    oColor = vec4(mix(fine, up, uSpread), 1.0);
}
)";

// picture + glow, "screen" so bright areas roll off instead of clipping. A glow around a transparent layer
// (cutout) adds alpha as well.
const char* kGlowComp = "#version 300 es\n"
R"(precision highp float;
uniform sampler2D uTex;
uniform sampler2D uGlow;
uniform float uGain;
in vec2 vUv;
out vec4 oColor;
)" AM_DITHER R"(
void main() {
    vec4 c = texture(uTex, vUv);
    vec3 g = clamp(texture(uGlow, vUv).rgb * uGain, 0.0, 1.0);
    vec3 rgb = c.rgb + g - c.rgb * g;
    float ga = max(g.r, max(g.g, g.b));
    float a = c.a + ga * (1.0 - c.a);
    rgb += amDither();
    oColor = vec4(clamp(rgb, 0.0, a), a);
}
)";

// Glow Scan (Alight Motion's `glowscan`, its recovered shader; fx_plan.cpp fxGlowScanMap is the CPU
// twin): uTex = the layer, uBlur = its blurred copy. uBand = (threshold, radius, power, blend) in
// Alight units, uFillColor the glow colour (straight), uMix = (fill, alpha). Inside the luma band the
// blurred picture gains pow(mix(base, fillColor * luma, blend * 0.99), power * lin^2); the result is
// clamped to the premultiplied invariant like kGlowComp. uBand.w (blend) is always 0 for now: the
// parameter has no native slot — the importer reports a non-zero blend instead of faking it.
const char* kGlowScan = R"(#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform sampler2D uBlur;
uniform vec4 uBand;
uniform vec3 uFillColor;
uniform vec2 uMix;
in vec2 vUv;
out vec4 oColor;
void main() {
    vec4 base = texture(uBlur, vUv);
    vec4 mainC = texture(uTex, vUv) * uMix.x;
    float luma = dot(base.rgb, vec3(0.2126, 0.7152, 0.0722));
    float adjT = uBand.x * (1.0 + uBand.y) - uBand.y;
    vec4 outC = mainC;
    if (uBand.y > 1e-6 && luma >= adjT && luma <= adjT + uBand.y) {
        float halfR = uBand.y * 0.5;
        float lin = abs((luma - adjT - halfR) / halfR);
        vec4 glow = pow(max(mix(base, vec4(uFillColor, 1.0) * luma, uBand.w * 0.99), vec4(0.0)), vec4(uBand.z * lin * lin));
        outC = mix(mainC, base + glow, uMix.y);
    }
    outC.a = clamp(outC.a, 0.0, 1.0);
    outC.rgb = clamp(outC.rgb, 0.0, outC.a);
    oColor = outC;
}
)";

// Luma Key (Alight Motion's `lumakey3`, its recovered shader; fx_plan.cpp fxLumaKeyAlpha is the CPU
// twin): the layer's alpha is scaled by where its (premultiplied) luma falls against the feathered
// [low, high] band. uBand = (low, high, feather, invert) in 0..1, uWeighted picks the luma weights.
const char* kLumaKey = R"(#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform vec4 uBand;
uniform float uWeighted;
in vec2 vUv;
out vec4 oColor;
void main() {
    vec4 c = texture(uTex, vUv);
    vec3 w = uWeighted > 0.5 ? vec3(0.2126, 0.7152, 0.0722) : vec3(0.3333, 0.3334, 0.3333);
    float luma = dot(c.rgb, w);
    float feather = uBand.z;
    float fw = max(feather, 0.00001);
    float lowB = uBand.x * (1.0 + feather) - 0.001;
    float lowA = lowB - fw;
    float highA = uBand.y * (1.0 + feather) - feather + 0.001;
    float highB = highA + fw;
    float alpha = min(smoothstep(lowA, lowB, luma), 1.0 - smoothstep(highA, highB, luma));
    if (uBand.w > 0.5) alpha = 1.0 - alpha;
    oColor = c * alpha;
}
)";

// Four Color Gradient (Alight Motion's `fourcolorgradient`, its recovered shader; fx_plan.cpp
// fxFourColorMap is the CPU twin): four colours anchored at four points blend by a smoothstep
// falloff of the distance, powered by 1 / blend and divided by the sum of the UNpowered weights,
// then composited over the layer per blend mode and mixed by alpha. uCol / uOff are unpacked on
// the CPU side; uMisc = (blend, alpha, blend mode). The gradient noise AM adds against banding is
// the engine's own dither here.
const char* kFourGradient = "#version 300 es\n"
R"(precision highp float;
uniform sampler2D uTex;
uniform vec3 uCol[4];
uniform vec2 uOff[4];
uniform vec3 uMisc;
in vec2 vUv;
out vec4 oColor;
)" AM_DITHER R"(
void main() {
    vec4 tex = texture(uTex, vUv);
    vec3 col = vec3(0.0);
    float wsum = 0.0;
    for (int i = 0; i < 4; i++) {
        vec2 pos = uOff[i] * vec2(1.0, -1.0) / 500.0;
        float t = length(vUv - pos);
        float sw = smoothstep(0.0, 1.0, clamp(1.0 - t, 0.0, 1.0));
        col += uCol[i] * pow(sw, 1.0 / max(uMisc.x, 0.001));
        wsum += sw;
    }
    col /= max(wsum, 0.000001);
    col += amDither();
    vec4 result;
    int bm = int(uMisc.z + 0.5);
    if (bm == 1) {
        result = vec4((tex.rgb / max(tex.a, 0.00001)) * col, 1.0) * tex.a;
    } else if (bm == 2) {
        result = vec4(1.0 - (1.0 - tex.rgb / max(tex.a, 0.00001)) * (1.0 - col), 1.0) * tex.a;
    } else {
        result = vec4(col, 1.0) * tex.a;
    }
    oColor = mix(tex, result, clamp(uMisc.y, 0.0, 1.0));
}
)";

// Turbulent Displace (Alight Motion's `turbulentdisplace`, its recovered shader; fx_plan.cpp
// fxSnoise3 / fxTurbulence are the CPU twins): the sampling is pushed by a simplex-noise
// turbulence field. uP / uQ carry the fx params (see FX_TURBULENT_DISPLACE_AM in fx_plan.h);
// uAspect is the picture's width / height. The frame convention is the warp programs' (vUv stands
// in for AM's layer / screen norm, the layer centre at 0.5). Outside the picture: transparent,
// as in Alight.
const char* kTurbulentAm = R"(#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform float uAspect;
uniform float uP[7];
uniform float uQ[7];
in vec2 vUv;
out vec4 oColor;
vec3 mod289(vec3 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
vec4 mod289(vec4 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
vec4 permute(vec4 x) { return mod289(((x * 34.0) + 1.0) * x); }
vec4 taylorInvSqrt(vec4 r) { return 1.79284291400159 - 0.85373472095314 * r; }
float snoise(vec3 v) {
    const vec2 C = vec2(1.0 / 6.0, 1.0 / 3.0);
    const vec4 D = vec4(0.0, 0.5, 1.0, 2.0);
    vec3 i = floor(v + dot(v, C.yyy));
    vec3 x0 = v - i + dot(i, C.xxx);
    vec3 g = step(x0.yzx, x0.xyz);
    vec3 l = 1.0 - g;
    vec3 i1 = min(g.xyz, l.zxy);
    vec3 i2 = max(g.xyz, l.zxy);
    vec3 x1 = x0 - i1 + C.xxx;
    vec3 x2 = x0 - i2 + C.yyy;
    vec3 x3 = x0 - D.yyy;
    i = mod289(i);
    vec4 p = permute(permute(permute(
        i.z + vec4(0.0, i1.z, i2.z, 1.0))
        + i.y + vec4(0.0, i1.y, i2.y, 1.0))
        + i.x + vec4(0.0, i1.x, i2.x, 1.0));
    float n_ = 0.142857142857;
    vec3 ns = n_ * D.wyz - D.xzx;
    vec4 j = p - 49.0 * floor(p * ns.z * ns.z);
    vec4 x_ = floor(j * ns.z);
    vec4 y_ = floor(j - 7.0 * x_);
    vec4 x = x_ * ns.x + ns.yyyy;
    vec4 y = y_ * ns.x + ns.yyyy;
    vec4 h = 1.0 - abs(x) - abs(y);
    vec4 b0 = vec4(x.xy, y.xy);
    vec4 b1 = vec4(x.zw, y.zw);
    vec4 s0 = floor(b0) * 2.0 + 1.0;
    vec4 s1 = floor(b1) * 2.0 + 1.0;
    vec4 sh = -step(h, vec4(0.0));
    vec4 a0 = b0.xzyw + s0.xzyw * sh.xxyy;
    vec4 a1 = b1.xzyw + s1.xzyw * sh.zzww;
    vec3 p0 = vec3(a0.xy, h.x);
    vec3 p1 = vec3(a0.zw, h.y);
    vec3 p2 = vec3(a1.xy, h.z);
    vec3 p3 = vec3(a1.zw, h.w);
    vec4 norm = taylorInvSqrt(vec4(dot(p0, p0), dot(p1, p1), dot(p2, p2), dot(p3, p3)));
    p0 *= norm.x;
    p1 *= norm.y;
    p2 *= norm.z;
    p3 *= norm.w;
    vec4 m = max(0.6 - vec4(dot(x0, x0), dot(x1, x1), dot(x2, x2), dot(x3, x3)), 0.0);
    m = m * m;
    return 42.0 * dot(m * m, vec4(dot(p0, x0), dot(p1, x1), dot(p2, x2), dot(p3, x3)));
}
float turbulence(vec2 st, int octaves, vec2 parallax, float evolution) {
    float value = 0.0;
    float amplitude = 0.5;
    for (int i = 0; i < 9; i++) {
        if (i >= octaves) break;
        value += amplitude * abs(snoise(vec3(st, evolution)));
        st = st * 2.0 + parallax;
        amplitude *= 0.5;
    }
    return value;
}
void main() {
    float intensity = clamp(uP[0], 0.0, 250.0) / 100.0;
    float scale = clamp(uP[1], 1.0, 5000.0) / 100.0;
    int direction = int(uP[2] + 0.5);
    float evolution = uP[3] / 100.0;
    int octaves = int(clamp(uP[4], 1.0, 9.0) + 0.5);
    float offs = uP[6] / 100.0;
    float stretch = uQ[0] / 100.0;
    float seed = uQ[1] / 100.0;
    vec2 st = vUv;
    st.x *= uAspect;
    st += vec2(uQ[2], uQ[3]) * vec2(-1.0, 1.0) / 500.0;
    vec2 parallax = vec2(uQ[4], uQ[5]) * vec2(-1.0, 1.0) / 100.0;
    vec2 scaleVec = stretch < 0.0 ? vec2(scale - stretch, scale) : vec2(scale, scale + stretch);
    vec2 cell = (st - 0.5) * scaleVec + 0.5;
    float p = turbulence(cell, octaves, parallax, evolution + 7391.935 * seed);
    p += (offs - 0.25);
    p *= intensity * 2.0;
    vec2 uv = vUv;
    if (direction == 0) {
        uv.x += p;
    } else if (direction == 1) {
        uv.y += p;
    } else if (direction == 2) {
        float q = turbulence(cell, octaves, parallax, evolution + 3937.7249 * (seed + 6.3379));
        q += (offs - 0.25);
        q *= intensity * 2.0;
        uv.x += p;
        uv.y += q;
    } else {
        vec2 d = uv - 0.5;
        float len = length(d);
        if (len > 0.000001) uv += d / len * p;
    }
    oColor = (all(greaterThanEqual(uv, vec2(0.0))) && all(lessThanEqual(uv, vec2(1.0))))
        ? texture(uTex, uv) : vec4(0.0);
}
)";

// Pixelate (Alight Motion's `pixelate2`, its recovered shader; fx_plan.cpp fxPixelateCentre is
// the CPU twin of the block-centre maths): the picture is re-read at each block's centre, with
// AM's alpha threshold / saturation handling and the cubic vignette darkening away from the
// block centres. uP / uQ carry the fx params (see FX_PIXELATE_AM in fx_plan.h), uSize the
// picture size in pixels. Outside the picture: transparent, as in Alight.
const char* kPixelateAm = R"(#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform vec2 uSize;
uniform float uP[7];
uniform float uQ[7];
in vec2 vUv;
out vec4 oColor;
mat2 rotm(float a) {
    float c = cos(a);
    float s = sin(a);
    return mat2(c, -s, s, c);
}
void main() {
    float size = clamp(uP[0], 1.0, 500.0);
    vec2 stretch = vec2(clamp(uP[1], 1.0, 10000.0), clamp(uP[2], 1.0, 10000.0)) / 100.0;
    float angle = uP[3];
    float vignette = clamp(uP[4], 0.0, 100.0) / 100.0;
    bool screenSpace = uP[5] > 0.5;
    float threshold = clamp(uP[6], 0.0, 100.0) / 100.0;
    float saturation = clamp(uQ[0], 0.0, 100.0) / 100.0;
    vec2 offset = vec2(uQ[1], uQ[2]);
    vec2 pixelSize = vec2(size) * stretch / uSize;
    vec2 st = vUv;
    if (!screenSpace) st -= 0.5;
    st = st * rotm(angle * 0.0174533);
    st -= offset / 1000.0;
    vec2 posInPixel = mod(st, pixelSize);
    posInPixel -= pixelSize / 2.0;
    posInPixel = posInPixel * rotm(-angle * 0.0174533);
    posInPixel += pixelSize / 2.0;
    vec2 pixelCenter = vUv - posInPixel + pixelSize / 2.0;
    float distCenter = smoothstep(0.0, 1.0, length((posInPixel / pixelSize) - vec2(0.5, 0.5)));
    vec4 color = (all(greaterThanEqual(pixelCenter, vec2(0.0))) && all(lessThanEqual(pixelCenter, vec2(1.0))))
        ? texture(uTex, pixelCenter) : vec4(0.0);
    float tclamp = step(threshold, color.a);
    if (color.a > 0.0 && saturation > 0.0) {
        color /= color.a / max(color.a, saturation);
    }
    color = color * tclamp;
    color = mix(color, vec4(min(color.rgb * color.rgb * color.rgb, vec3(0.9, 0.9, 0.9)), color.a * color.a * color.a),
                step(1.5, size) * vignette * distCenter);
    oColor = color;
}
)";

// RGB split: 9 taps across the offset, each tap contributes to the channels like a spectrum (blue at one end,
// green in the middle, red at the other), so the fringes are smooth and not three ghost copies.
const char* kSplit = R"(#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform vec2 uTexel;
uniform float uPx;
uniform vec2 uDir;
uniform float uRadial;
uniform float uAspect;
in vec2 vUv;
out vec4 oColor;
void main() {
    vec2 centre = vUv - 0.5;
    vec2 lin = uDir * uPx;
    vec2 rad = centre * vec2(uAspect, 1.0) * 2.0 * uPx;
    vec2 off = mix(lin, rad, uRadial) * uTexel;
    vec3 acc = vec3(0.0);
    vec3 wsum = vec3(0.0);
    float asum = 0.0;
    for (int k = 0; k < 9; k++) {
        float s = float(k) / 8.0;
        vec4 c = texture(uTex, vUv + off * (s * 2.0 - 1.0));
        vec3 w = max(vec3(0.0), 1.0 - abs(vec3(s) - vec3(1.0, 0.5, 0.0)) * 2.0);
        acc += c.rgb * w;
        wsum += w;
        asum += c.a;
    }
    oColor = vec4(acc / wsum, asum / 9.0);
}
)";

// Film grain: triangular noise per cell, bilinear between cells when the grain is bigger than a pixel (unit
// variance kept), strongest in the mid tones, optional colour grain.
const char* kGrain = R"(#version 300 es
precision highp float;
precision highp int;
uniform sampler2D uTex;
uniform float uCell;
uniform float uAmount;
uniform float uColor;
uniform int uFrame;
in vec2 vUv;
out vec4 oColor;
uint pcg(uint v) {
    uint st = v * 747796405u + 2891336453u;
    uint w = ((st >> ((st >> 28u) + 4u)) ^ st) * 277803737u;
    return (w >> 22u) ^ w;
}
float hash(ivec2 p, int k) {
    uint x = uint(p.x + 65536);
    uint y = uint(p.y + 65536);
    uint h = pcg(x ^ pcg(y ^ pcg(uint(uFrame) * 16u + uint(k))));
    return float(h) * (1.0 / 4294967295.0);
}
float tri(ivec2 p, int ch) {
    return (hash(p, ch * 2) + hash(p, ch * 2 + 1) - 1.0) * 2.4494897;  // unit variance
}
float noise(int ch) {
    if (uCell < 1.01) return tri(ivec2(floor(gl_FragCoord.xy)), ch);
    vec2 q = gl_FragCoord.xy / uCell;
    ivec2 i0 = ivec2(floor(q));
    vec2 f = fract(q);
    float w00 = (1.0 - f.x) * (1.0 - f.y), w10 = f.x * (1.0 - f.y);
    float w01 = (1.0 - f.x) * f.y, w11 = f.x * f.y;
    float n = tri(i0, ch) * w00 + tri(i0 + ivec2(1, 0), ch) * w10
            + tri(i0 + ivec2(0, 1), ch) * w01 + tri(i0 + ivec2(1, 1), ch) * w11;
    return n * inversesqrt(w00 * w00 + w10 * w10 + w01 * w01 + w11 * w11);
}
void main() {
    vec4 c = texture(uTex, vUv);
    float a = max(c.a, 0.0001);
    float lu = dot(c.rgb, vec3(0.2126, 0.7152, 0.0722)) / a;
    float mid = 1.0 - pow(abs(2.0 * lu - 1.0), 2.0);
    float amp = uAmount * 0.2 * mix(0.35, 1.0, mid);
    float n0 = noise(0);
    vec3 n = vec3(n0);
    if (uColor > 0.001) {
        n = vec3(mix(n0, noise(1), uColor), n0, mix(n0, noise(2), uColor));
    }
    vec3 rgb = c.rgb + n * amp * c.a;
    oColor = vec4(clamp(rgb, 0.0, c.a), c.a);
}
)";

const char* kVig = "#version 300 es\n"
R"(precision highp float;
uniform sampler2D uTex;
uniform float uAmount;
uniform vec2 uEdge;
uniform float uAspect;
in vec2 vUv;
out vec4 oColor;
)" AM_DITHER R"(
void main() {
    vec4 c = texture(uTex, vUv);
    vec2 p = (vUv - 0.5) * vec2(uAspect, 1.0) * 2.0;
    float d = length(p) / length(vec2(uAspect, 1.0));
    float m = smoothstep(uEdge.x, uEdge.y, d);
    m = m * m * (3.0 - 2.0 * m);
    float k = 1.0 - uAmount * m;
    vec3 rgb = c.rgb * k + amDither() * c.a;
    oColor = vec4(clamp(rgb, 0.0, c.a), c.a);
}
)";


// ---- Masks ----
// One pass per mask: coverage of the shape (distance field, feather, expansion, invert, opacity) combined with the
// coverage of the masks before it (uPrev). Same maths as mask_plan.cpp (maskShapeDistance / maskCoverage / maskCombine).
const char* kMask = "#version 300 es\n"
R"(precision highp float;
uniform sampler2D uPrev;
uniform vec2 uSize;
uniform int uShape;
uniform int uMode;
uniform float uInvert;
uniform vec2 uC;
uniform vec2 uHalf;
uniform vec2 uRot;
uniform float uFeather;
uniform float uExpand;
uniform float uOpacity;
uniform float uCorner;
uniform float uAn;
uniform vec2 uAnCs;
uniform vec2 uEn;
uniform int uN;
uniform vec2 uPts[96];
in vec2 vUv;
out float oMask;

float sdRoundBox(vec2 p, vec2 h, float r) {
    vec2 q = abs(p) - h + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}
float sdEllipse(vec2 p, vec2 ab) {
    float k0 = length(p / ab);
    float k1 = length(p / (ab * ab));
    return k1 > 1e-12 ? k0 * (k0 - 1.0) / k1 : -min(ab.x, ab.y);
}
float sdStar(vec2 p) {
    float len = length(p);
    float a = len > 1e-6 ? atan(p.x, p.y) : 0.0;
    float step = 2.0 * uAn;
    a = a - step * floor(a / step);
    float bn = a - uAn;
    vec2 q = len * vec2(cos(bn), abs(sin(bn)));
    q -= uAnCs;
    q += uEn * clamp(-dot(q, uEn), 0.0, uAnCs.y / uEn.y);
    return length(q) * (q.x < 0.0 ? -1.0 : 1.0);
}
float sdPoly(vec2 p) {
    float d = dot(p - uPts[0], p - uPts[0]);
    float s = 1.0;
    for (int i = 0, j = uN - 1; i < 96; j = i, i++) {
        if (i >= uN) break;
        vec2 e = uPts[j] - uPts[i];
        vec2 w = p - uPts[i];
        float ee = dot(e, e);
        float h = ee > 1e-12 ? clamp(dot(w, e) / ee, 0.0, 1.0) : 0.0;
        vec2 b = w - e * h;
        d = min(d, dot(b, b));
        bool c1 = p.y >= uPts[i].y;
        bool c2 = p.y < uPts[j].y;
        bool c3 = e.x * w.y > e.y * w.x;
        if ((c1 && c2 && c3) || (!c1 && !c2 && !c3)) s = -s;
    }
    return s * sqrt(d);
}
void main() {
    vec2 p = vec2(vUv.x * uSize.x, (1.0 - vUv.y) * uSize.y);
    float d;
    if (uShape == 6) {
        d = sdPoly(p);
    } else {
        vec2 dp = p - uC;
        vec2 l = vec2(dp.x * uRot.x + dp.y * uRot.y, -dp.x * uRot.y + dp.y * uRot.x);
        if (uShape == 0) d = sdRoundBox(l, uHalf, uCorner);
        else if (uShape == 1) d = sdEllipse(l, uHalf);
        else if (uShape == 2 || uShape == 3) d = sdStar(vec2(l.x / uHalf.x, -l.y / uHalf.y)) * min(uHalf.x, uHalf.y);
        else if (uShape == 4) d = l.y;
        else d = abs(l.y) - uHalf.y;
    }
    d -= uExpand;
    float w = max(2.0 * uFeather, 1.0);
    float t = clamp(0.5 - d / w, 0.0, 1.0);
    float cov = t * t * (3.0 - 2.0 * t);
    cov = (uInvert > 0.5 ? 1.0 - cov : cov) * uOpacity;
    float prev = texture(uPrev, vUv).r;
    float r;
    if (uMode == 1) r = prev * (1.0 - cov);
    else if (uMode == 2) r = prev * cov;
    else if (uMode == 3) r = prev + cov - 2.0 * prev * cov;
    else r = prev + cov - prev * cov;
    oMask = r;
}
)";

// The layer (premultiplied) times the coverage of all masks.
const char* kMaskApply = R"(#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform sampler2D uMask;
in vec2 vUv;
out vec4 oColor;
void main() { oColor = texture(uTex, vUv) * texture(uMask, vUv).r; }
)";

// Chroma key: works on the premultiplied picture. Unpremultiply, take the chroma (BT.601, full range), keep the pixel
// by its distance to the key colour in the CbCr plane (smoothstep edge), remove the key colour's share from the kept
// pixels (spill: the green / blue reflection on hair and skin), premultiply again with the new alpha.
// The key distance uses the chroma divided by the brightest channel (a screen in shadow keys like a lit one); for a grey /
// white / black key the brightness difference (uVW) counts too, so ANY colour can be keyed, not only green / blue.
// Same maths as fx_plan.cpp (fxChromaKeyed / fxChromaKeep).
const char* kChroma = "#version 300 es\n"
R"(precision highp float;
uniform sampler2D uTex;
uniform vec2 uKey;
uniform vec2 uDir;
uniform float uTol;
uniform float uSoft;
uniform float uSpill;
uniform float uKeyV;
uniform float uVW;
uniform float uCS;
in vec2 vUv;
out vec4 oColor;
void main() {
    vec4 c = texture(uTex, vUv);
    if (c.a <= 0.0005) { oColor = vec4(0.0); return; }
    vec3 rgb = c.rgb / c.a;
    float y = dot(rgb, vec3(0.299, 0.587, 0.114));
    vec2 cc = vec2((rgb.b - y) * 0.564, (rgb.r - y) * 0.713);
    float v = max(0.2, max(rgb.r, max(rgb.g, rgb.b)));
    float dv = (max(rgb.r, max(rgb.g, rgb.b)) - uKeyV) * uVW;
    vec2 dc = (cc / v - uKey) * uCS;
    float d = sqrt(dot(dc, dc) + dv * dv);
    float keep = smoothstep(uTol, uTol + uSoft, d);
    // spill: take the part of the chroma that points towards the key colour out, only where it points that way
    float along = dot(cc, uDir);
    float cut = max(along, 0.0) * uSpill;
    vec2 cs = cc - uDir * cut;
    float b = y + cs.x / 0.564;
    float r = y + cs.y / 0.713;
    float g = (y - 0.299 * r - 0.114 * b) / 0.587;
    vec3 outRgb = clamp(vec3(r, g, b), 0.0, 1.0);
    float a = c.a * keep;
    oColor = vec4(outRgb * a, a);
}
)";

// Chroma Key (Alight Motion's `chromakey`, its recovered shader; fx_plan.cpp fxChromaKeyAmMap is
// the CPU twin): NOT the app's own FX_CHROMA above — AM measures the distance to the key colour
// in its YUV space (luma counted a quarter) on the UNpremultiplied pixel, defringes the
// premultiplied pixel by the key colour, and forces the half-texel border transparent.
// uKey is the key colour (straight), uParams = (threshold, feather, defringe, invert) in 0..1.
const char* kChromaKeyAm = R"(#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform vec3 uKey;
uniform vec4 uParams;
in vec2 vUv;
out vec4 oColor;
void main() {
    mat3 rgb2yuv = mat3(0.299, 0.587, 0.114, -0.14713, -0.28886, 0.436, 0.615, -0.51499, -0.10001);
    vec3 keyYUV = uKey * rgb2yuv;
    vec4 texColor = texture(uTex, vUv);
    vec3 texYUV = (texColor.a <= 0.0 ? vec3(0.0) : texColor.rgb / texColor.a) * rgb2yuv;
    float dist = length((keyYUV - texYUV) * vec3(0.25, 1.0, 1.0));
    float alpha = smoothstep(uParams.x - uParams.y / 2.0, uParams.x + uParams.y / 2.0, dist);
    if (uParams.w > 0.5) alpha = 1.0 - alpha;
    if (uParams.z > 0.5) texColor -= vec4(uKey, 1.0) * (1.0 - alpha);
    vec2 halfFrag = 0.5 * vec2(dFdx(vUv.x), dFdy(vUv.y));
    if (vUv.x <= halfFrag.x || vUv.x >= 1.0 - halfFrag.x || vUv.y <= halfFrag.y || vUv.y >= 1.0 - halfFrag.y) {
        alpha = 0.0;
    }
    oColor = vec4(texColor.rgb, 1.0) * alpha * texColor.a;
}
)";

// Soft Glow (Alight Motion's `softglow`, its recovered shader; fx_plan.cpp fxSoftGlowAmMap is
// the CPU twin): uTex = the layer, uBlur = its blurred copy. The blurred copy, brightened and
// pushed toward the tint colour by its own luminance, is SCREEN-blended over the layer.
// uAdj = (brightness, contrast, highlights, blend), uMisc = (colour packed, alpha); the
// backdrop branch (AM's `comp` texture, outsideAlpha > 0) is not reproduced — the importer
// warns when an instance uses it. A fully transparent pixel stays transparent (AM divides by
// its alpha there; the result is invisible either way).
const char* kSoftGlow = R"(#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform sampler2D uBlur;
uniform vec4 uAdj;
uniform vec3 uColor;
uniform float uAlpha;
in vec2 vUv;
out vec4 oColor;
void main() {
    vec4 texColor = texture(uTex, vUv);
    if (texColor.a <= 0.0005) { oColor = vec4(0.0); return; }
    vec4 blurTexColor = texture(uBlur, vUv);
    vec4 adjBlur = blurTexColor;
    adjBlur.rgb = min(adjBlur.rgb * uAdj.x, vec3(1.0));
    float luminance = min(1.0, dot(adjBlur.rgb, vec3(0.2126, 0.7152, 0.0722)) * uAdj.x);
    vec4 blendColor = mix(adjBlur, luminance * vec4(uColor, 1.0), uAdj.w);
    blendColor.rgb = mix(blendColor.rgb, vec3(luminance), luminance * uAdj.z);
    blendColor.rgb = blendColor.rgb * mix(vec3(1.0), blendColor.rgb, uAdj.y);
    vec4 blendInside = blendColor * uAlpha;
    vec3 straight = texColor.rgb / texColor.a;
    vec3 res = 1.0 - (1.0 - straight) * (1.0 - blendInside.rgb);
    oColor = vec4(clamp(res, 0.0, 1.0) * texColor.a, texColor.a);
}
)";

// Fill Behind (Alight Motion's `fillbehind`, its recovered shader; fx_plan.cpp
// fxFillBehindAmBg is the CPU twin of the background colour pipeline): the blurred copy,
// colour-adjusted, fills behind the layer's own (feathered) foreground, with an edge-colour
// rim where the blurred alpha sits between. The layer-to-screen scale of AM's background
// sampling reduces to (1 + strength) for a layer at its own size (its transform is applied
// outside this pass, like every effect here). uBgP = (tint strength, saturation, gamma,
// background scale), uFgP = (scan lines, scan line count, edge strength, alpha),
// uFeaP = (feather, strength) for the foreground alpha curve, uBgOff the background offset.
const char* kFillBehind = R"(#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform sampler2D uBlur;
uniform vec2 uBgOff;
uniform vec3 uTint;
uniform vec4 uBgP;
uniform vec4 uFgP;
uniform vec3 uEdge;
uniform vec2 uFeaP;
in vec2 vUv;
out vec4 oColor;
vec4 composite(vec4 a, vec4 b) { return a * (1.0 - b.a) + b; }
void main() {
    mat3 rgb2yuv = mat3(0.299, 0.587, 0.114, -0.14713, -0.28886, 0.436, 0.615, -0.51499, -0.10001);
    mat3 yuv2rgb = mat3(1.0, 0.0, 1.13983, 1.0, -0.39465, -0.58060, 1.0, 2.03211, 0.0);
    vec2 stBG = (vUv - 0.5) / uBgP.w + 0.5 - uBgOff / 500.0 * vec2(1.0, -1.0);
    vec2 bgSample = (stBG - 0.5) / (1.0 + uFeaP.y) + 0.5;
    vec4 texColor = texture(uTex, vUv);
    float blurAlpha = texture(uBlur, vUv).a;
    vec4 blurTexColor = texture(uBlur, bgSample);
    vec3 tintYuv = uTint * rgb2yuv;
    vec3 rgb = blurTexColor.a > 0.0 ? blurTexColor.rgb / blurTexColor.a : vec3(0.0);
    vec3 yuv = rgb * rgb2yuv;
    yuv.yz = yuv.yz * (uBgP.y + 1.0);
    yuv.yz = mix(yuv.yz, tintYuv.yz, uBgP.x);
    rgb = yuv * yuv2rgb;
    rgb = clamp(pow(max(rgb, vec3(0.0)), vec3(1.0 / uBgP.z)), 0.0, 1.0);
    vec4 adjBlurColor = vec4(rgb, 1.0) * blurTexColor.a;
    adjBlurColor.rgb *= 1.0 - (uFgP.x * cos(vUv.y * uFgP.y));
    float fa = uFeaP.x < 0.0005 ? 1.0
               : smoothstep(0.4999, 0.5 + min(0.999, uFeaP.x / max(uFeaP.y, 1e-6)) * 0.5, blurAlpha);
    vec4 fg = composite(vec4(uEdge, 1.0) * (blurAlpha * 2.0 * uFgP.z), texColor * fa);
    oColor = composite(adjBlurColor * uFgP.w, fg);
}
)";

// Dark Glow (Alight Motion's `darkglow`, its recovered def; fx_plan.cpp fxDarkGlowExtract /
// fxDarkGlowComposite are the CPU twins): pass 0 keeps the pixels whose luma is below the
// threshold, mixed toward the colour by blend; the extract is blurred (the def's kernel, via
// fxDarkGlowSigma) and the composite multiplies the blurred extract's straight colour by the
// base's straight colour, over the base at min(base.a, extract.a) * alpha. AM runs the chain
// on half-size buffers; here it runs at the layer's own size with the sigma doubled to match
// (see fxDarkGlowSigma), so the base keeps its full sharpness — a softness-only difference.
const char* kDarkGlowExtract = R"(#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform vec3 uColor;
uniform vec2 uP;   // threshold, blend (0..1)
in vec2 vUv;
out vec4 oColor;
void main() {
    vec4 c = texture(uTex, vUv);
    float luminance = dot(c.rgb, vec3(0.2126, 0.7152, 0.0722));
    oColor = luminance < uP.x ? mix(c, vec4(uColor, 1.0) * c.a, uP.y) : vec4(0.0);
}
)";

const char* kDarkGlowComp = R"(#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform sampler2D uBlur;
uniform float uAlpha;
in vec2 vUv;
out vec4 oColor;
void main() {
    vec4 base = texture(uTex, vUv);
    vec4 res = texture(uBlur, vUv);
    float glowA = min(base.a, res.a) * uAlpha;
    vec3 glow = (res.rgb / max(res.a, 0.0001)) * (base.rgb / max(base.a, 0.0001));
    vec4 g = vec4(glow, 1.0) * glowA;
    oColor = base * (1.0 - g.a) + g;
}
)";

// Edge Glow (Alight Motion's `edgeglow`, its recovered def — two independent dumps carry
// it identically; fx_plan.cpp fxEdgeGlowAmEdge / fxEdgeGlowAmComposite are the CPU twins):
// pass 1 is the def's findedges-edgeglow — a Scharr Sobel on the premultiplied luma with
// taps at +/- smoothing texels, g = clamp(|grad| - threshold, 0, 1), out = g * texColor
// (or (1 - g) * texColor.a when inverted). The magnitude is symmetric under the picture's
// y orientation, so AM's screen-norm sampling and the layer-space vUv here agree. Pass 2
// (in fxEdgeGlowAm) is the host gaussianblur of the edge buffer at the def's strength
// (its label is "Blur"; fxEdgeGlowAmBlurSigma converts it). The composite below is the def's
// final shader line by line; its only guard is max(a, 0.0001) on the two un-premultiply
// divisions, where AM's raw division is 0/0 on invisible pixels.
const char* kEdgeGlowEdge = R"(#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform vec2 uTexel;
uniform vec3 uP;   // smoothing, threshold, invert (0/1)
in vec2 vUv;
out vec4 oColor;
float lum(vec2 o) { return dot(texture(uTex, vUv + o).rgb, vec3(0.2126, 0.7152, 0.0722)); }
void main() {
    vec2 f = uTexel * uP.x;
    float w = lum(vec2(-f.x, 0.0));
    float e = lum(vec2(f.x, 0.0));
    float n = lum(vec2(0.0, f.y));
    float s = lum(vec2(0.0, -f.y));
    float nw = lum(vec2(-f.x, f.y));
    float ne = lum(vec2(f.x, f.y));
    float sw = lum(vec2(-f.x, -f.y));
    float se = lum(vec2(f.x, -f.y));
    float v = nw * 3.0 + n * 10.0 + ne * 3.0 - sw * 3.0 - s * 10.0 - se * 3.0;
    float h = -nw * 3.0 - w * 10.0 - sw * 3.0 + ne * 3.0 + e * 10.0 + se * 3.0;
    float g = clamp(sqrt(h * h + v * v) - uP.y, 0.0, 1.0);
    vec4 texColor = texture(uTex, vUv);
    oColor = uP.z > 0.5 ? vec4(vec3(1.0 - g), 1.0) * texColor.a : vec4(vec3(g), 1.0) * texColor;
}
)";

const char* kEdgeGlowComp = R"(#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform sampler2D uBlur;
uniform vec3 uFill;
uniform vec3 uTint;
uniform vec4 uP;   // spread, gamma, brightness, tint
uniform float uBlend;
in vec2 vUv;
out vec4 oColor;
void main() {
    mat3 rgb2yuv = mat3(0.299, 0.587, 0.114, -0.14713, -0.28886, 0.436, 0.615, -0.51499, -0.10001);
    mat3 yuv2rgb = mat3(1.0, 0.0, 1.13983, 1.0, -0.39465, -0.58060, 1.0, 2.03211, 0.0);
    vec4 glow = texture(uBlur, vUv);
    vec4 base = texture(uTex, vUv);
    glow.rgb /= max(glow.a, 0.0001);
    base.rgb /= max(base.a, 0.0001);
    glow.rgb = pow(glow.rgb, vec3(max(0.0001, 3.0 - uP.x)));
    base.rgb = pow(base.rgb, vec3(1.0 / max(uP.y, 0.01)));
    base.rgb = ((base.rgb * rgb2yuv) + vec3(uP.z, 0.0, 0.0)) * yuv2rgb;
    base.rgb = clamp(base.rgb, 0.0, 1.0);
    float lum = dot(vec3(0.2126, 0.7152, 0.0722), glow.rgb);
    glow.rgb *= glow.a;
    base.rgb *= base.a;
    float factor = max(clamp(uBlend, 0.0, 1.0) * (1.0 - lum), uBlend - 1.0);
    oColor = mix(base, base * vec4(uTint, 1.0), uP.w) +
             mix(glow, vec4(uFill, 1.0) * lum, factor);
}
)";

// Deep Glow 2 (Alight's deepglow2 AND deepglow_enc — one def, two ids; hunt round 3,
// defs-batch8): the def's composite, line by line. uTex is the layer, uBlur its
// gaussianblur buffer. The def's spaces: the glow offset (position) and the chromatic
// offset are in screen norm / layer norm — anchored to the layer picture here (its
// vUv), the house convention for AM's norm spaces (Fill Behind's uBgOff, Hex Shift);
// position / stretch / OFT sit at their def defaults (0) in every corpus instance,
// so the anchoring is inert there. uP = strength, oalpha, lowThreshold,
// highThreshold; uQ = feather, calpha, hue (raw degrees, folded to turns by the
// caller), alpb; uCol the key colour (straight); uOff the chromatic offset, ready
// in vUv (converted CPU-side); uGlowOff = position, stretch (AM units; the shader
// applies the / 500 and the stretch warp, as the def does); uFlags = (overd, GCE,
// CHROM, centerChannel 0..2), uFlags2 = (cblend, cmode). fxDeepGlow2Pixel in
// fx_plan.cpp is the CPU twin. The un-premultiply divides by max(a, 0.0001) (the
// edgeglow-comp guard).
const char* kDeepGlowComp = R"(#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform sampler2D uBlur;
uniform vec4 uP;
uniform vec4 uQ;
uniform vec3 uCol;
uniform vec2 uOff;   // the chromatic offset (CPU-side: dir(angleee) x strengthhh / 8
                     // + OFT x (-1, 1) / 500, the def's layer-norm formula)
uniform vec4 uGlowOff;
uniform vec4 uSat;   // satvib x, satvib y, strengthhh, 0
uniform vec4 uFlags;   // overd, GCE, CHROM, centerChannel (0..2)
uniform vec2 uFlags2;   // cblend, cmode
uniform float uBmode;
in vec2 vUv;
out vec4 oColor;
vec4 newLayer(vec4 a, vec4 b) { return a * (1.0 - b.a) + b; }
float ss(float a, float b, float x) { float t = clamp((x - a) / (b - a), 0.0, 1.0); return t * t * (3.0 - 2.0 * t); }
void main() {
    mat3 rgb2yuv = mat3(0.299, 0.587, 0.114, -0.14713, -0.28886, 0.436, 0.615, -0.51499, -0.10001);
    mat3 yuv2rgb = mat3(1.0, 0.0, 1.13983, 1.0, -0.39465, -0.58060, 1.0, 2.03211, 0.0);
    vec4 texColor = texture(uTex, vUv);
    vec4 baseColor = texColor * uQ.y;
    float ang = 6.2831855 * uQ.z;
    mat3 huemat = mat3(1.0, 0.0, 0.0,
                       0.0, cos(ang), sin(ang),
                       0.0, -sin(ang), cos(ang));
    vec2 ume = vUv + uGlowOff.xy / 500.0 * vec2(-1.0, 1.0);
    ume -= (ume - 0.5) * 0.7 * vec2(fract(uGlowOff.z * 0.5), fract(uGlowOff.w * 0.5));
    vec4 mid = texture(uBlur, ume);
    vec4 compColorr = mid;
    if (uFlags.z > 0.5) {
        vec2 off = uOff;
        vec4 lo = texture(uBlur, vUv - off);
        vec4 hi = texture(uBlur, vUv + off);
        int cc = int(uFlags.w + 0.5);
        if (cc == 0) compColorr = vec4(mid.r, lo.g, hi.b, mid.a);
        else if (cc == 1) compColorr = vec4(lo.r, mid.g, hi.b, mid.a);
        else compColorr = vec4(lo.r, hi.g, mid.b, mid.a);
    }
    vec4 gammaComp = compColorr;
    if (uFlags.y > 0.5) gammaComp = vec4(pow(max(compColorr.rgb, vec3(0.0)), vec3(1.0 / 2.2)), compColorr.a) * compColorr.a;
    vec3 yuv = gammaComp.rgb * rgb2yuv;
    yuv *= huemat;
    float origSat = min(1.0, length(yuv.yz) * 2.5);
    yuv.yz = mix(yuv.yz * uSat.y, yuv.yz, origSat);
    yuv.yz *= (uSat.x + 1.0);
    vec4 satComp = vec4(yuv * yuv2rgb, gammaComp.a);
    vec4 dblur = vec4(satComp.rgb / max(satComp.a, 0.0001), 1.0);
    float lumafxC = dot(vec3(0.2126, 0.7152, 0.0722), dblur.rgb);
    float lowB = uP.z * (1.0 + uQ.x) - 0.0001;
    float lowA = lowB - max(uQ.x, 0.00001);
    float highA = uP.w * (1.0 + uQ.x) - uQ.x + 0.0001;
    float highB = highA + max(uQ.x, 0.00001);
    float lumafx = min(ss(lowA, lowB, lumafxC), 1.0 - ss(highA, highB, lumafxC));
    vec4 dblurr = dblur;
    if (uP.z > 0.001 || uP.w < 0.001) dblurr *= lumafx;
    dblurr *= satComp.a;
    dblurr *= uP.y;
    vec4 blendComp = dblurr;
    if (uFlags2.x > 0.5) {
        vec3 blendGlow = uFlags2.y > 0.5 ? uCol : dblurr.rgb + uCol;
        blendComp = mix(dblurr, vec4(blendGlow, dblurr.a), uQ.w) * dblurr.a;
    }
    vec4 finalGlow;
    int bm = int(uBmode + 0.5);
    if (bm == 0) finalGlow = newLayer(baseColor, vec4(1.0 - (1.0 - baseColor.rgb) * (1.0 - blendComp.rgb), blendComp.a));
    else if (bm == 1) finalGlow = baseColor / (1.0 - blendComp);
    else if (bm == 3) finalGlow = newLayer(baseColor, blendComp);
    else finalGlow = baseColor + blendComp;
    vec4 outc = uP.x > 0.00001 ? finalGlow : texColor;
    if (uFlags.x > 0.5) outc *= texColor.a;
    oColor = outc;
}
)";

// Drop Shadow (Alight's dropshadow; hunt round 3, defs-batch8): the def's composite,
// line by line. uTex is the layer, uBlur its gaussianblur buffer; uShadow the shadow
// colour (straight rgba), uOff the position offset in uv (position x (-1, 1) / 500,
// the def's screen-norm space anchored to the layer picture — the house convention),
// uP = alpha, alphac. fxDropShadowPixel in fx_plan.cpp is the CPU twin.
const char* kDropShadow = R"(#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform sampler2D uBlur;
uniform vec4 uShadow;
uniform vec2 uOff;
uniform vec2 uP;
in vec2 vUv;
out vec4 oColor;
void main() {
    vec4 texColor = texture(uTex, vUv) * uP.y;
    vec4 shadowb = uShadow * texture(uBlur, vUv + uOff).a;
    oColor = shadowb * (1.0 - texColor.a) + texColor;
}
)";

// ---- v177 Queue 15(f) tier-5 programs. Each mirrors its fx_plan.cpp CPU twin
// line by line (the twins carry the def citations); the selftest pins anchor
// twin and shader to the same hand-computed values.
const char* kBbMaker = R"(#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform float uP[7];
uniform float uQ[7];
in vec2 vUv;
out vec4 oColor;
float sstep(float e0, float e1, float x) {
    float t = clamp((x - e0) / (e1 - e0), 0.0, 1.0);
    return t * t * (3.0 - 2.0 * t);
}
vec3 unpackRgb(float pk) {
    pk = floor(clamp(pk, 0.0, 16777215.0) + 0.5);
    return vec3(mod(floor(pk / 65536.0), 256.0), mod(floor(pk / 256.0), 256.0), mod(pk, 256.0)) / 255.0;
}
float band(float start, float end, float feather) {
    float rad = uP[1] * 0.0174533;
    vec2 v = normalize(vec2(cos(rad), -sin(rad)));
    float p = dot(vUv - 0.5, v) + 0.5;
    return sstep(start - feather, start, p) * (1.0 - sstep(end, end + feather, p));
}
void main() {
    vec4 tex = texture(uTex, vUv);
    vec4 wipebb = vec4(unpackRgb(uP[2]), uP[3]) * tex.a;
    wipebb *= band(uP[4], uP[5], uQ[0] / 100.0);
    vec4 tb = tex * band((uP[0] - 20.0) / 100.0, (120.0 - uP[0]) / 100.0, 0.0);
    vec4 layer = wipebb * (1.0 - tb.a) + tb;
    oColor = layer * tex.a * uQ[1];
}
)";

const char* kUnsharpComp = R"(#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform sampler2D uBlur;
uniform float uP[7];
in vec2 vUv;
out vec4 oColor;
float sstep(float e0, float e1, float x) {
    float t = clamp((x - e0) / (e1 - e0), 0.0, 1.0);
    return t * t * (3.0 - 2.0 * t);
}
void main() {
    vec4 base = texture(uTex, vUv);
    vec4 blur = texture(uBlur, vUv);
    vec3 adj = base.rgb * (1.0 + uP[1]) - blur.rgb * uP[1];
    float baseY = dot(base.rgb, vec3(0.299, 0.587, 0.114));
    float blurY = dot(blur.rgb, vec3(0.299, 0.587, 0.114));
    float t = sstep(uP[2] * 0.75, (uP[2] + 1.01) / 2.0, abs(blurY - baseY) * 4.0);
    if (uP[3] > 0.5) {
        oColor = vec4(vec3(t), 1.0) * base.a;
    } else {
        oColor = vec4(mix(base.rgb, adj, t), base.a);
    }
}
)";

const char* kNewSharpenComp = R"(#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform sampler2D uBlur;
uniform vec2 uVel;
uniform int uSteps;
uniform float uP[7];
in vec2 vUv;
out vec4 oColor;
void main() {
    vec4 man = texture(uTex, vUv);
    vec4 acc = texture(uBlur, vUv);
    for (int i = 1; i < 100; i++) {
        if (i >= uSteps) break;
        vec2 off = uVel * (float(i) / float(uSteps - 1));
        acc += texture(uBlur, vUv + off) + texture(uBlur, vUv - off);
    }
    vec4 result = acc / float(2 * uSteps - 1);
    vec4 blured = (man - result) * uP[3];
    float all = (blured.r + blured.g + blured.g) / 3.0;
    vec4 white = uP[1] > 0.5 ? vec4(all, all, all, 1.0) * man.a : blured;
    oColor = uP[2] > 0.5 ? white : 1.0 - (1.0 - man) * (1.0 - white);
}
)";

const char* kSpillStreak = R"(#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform vec2 uAxis;
uniform float uDist;
uniform int uSteps;
in vec2 vUv;
out vec4 oColor;
void main() {
    vec4 c = vec4(0.0);
    for (int i = 1; i < 100; i++) {
        if (i >= uSteps) break;
        float o = uDist * (float(i) / float(uSteps - 1) - 0.5);
        vec4 tc = texture(uTex, vUv + uAxis * o);
        if (o < 0.0) c = c * (1.0 - tc.a) + tc;
        else c = tc * (1.0 - c.a) + c;
    }
    oColor = c;
}
)";

const char* kSpillOver = R"(#version 300 es
precision highp float;
uniform sampler2D uBottom;
uniform sampler2D uTop;
in vec2 vUv;
out vec4 oColor;
void main() {
    vec4 b = texture(uBottom, vUv);
    vec4 t = texture(uTop, vUv);
    oColor = b * (1.0 - t.a) + t;
}
)";

const char* kSpillFinal = R"(#version 300 es
precision highp float;
uniform sampler2D uBase;
uniform sampler2D uBlur;
uniform sampler2D uStreak;
in vec2 vUv;
out vec4 oColor;
vec3 toYuv(vec3 c) {
    mat3 m = mat3(0.299, 0.587, 0.114, -0.14713, -0.28886, 0.436, 0.615, -0.51499, -0.10001);
    return c * m;
}
vec3 toRgb(vec3 c) {
    mat3 m = mat3(1.0, 0.0, 1.13983, 1.0, -0.39465, -0.58060, 1.0, 2.03211, 0.0);
    return c * m;
}
void main() {
    vec4 base = texture(uBase, vUv);
    vec4 blur = texture(uBlur, vUv);
    vec4 streak = texture(uStreak, vUv);
    float sat = clamp((1.0 - blur.a) * 2.0, 0.0, 1.0);
    vec4 c = streak * sat * base.a;
    vec3 byuv = toYuv(base.rgb / max(base.a, 0.00001));
    vec3 cyuv = toYuv(c.rgb / max(c.a, 0.00001));
    vec4 cc = vec4(toRgb(vec3(byuv.x, cyuv.y, cyuv.z)) * c.a, c.a);
    oColor = base * (1.0 - cc.a) + cc;
}
)";

const char* kAmBlendGen = R"(#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform float uP[7];
uniform float uQ[7];
uniform float uR[7];
uniform int uKind;
uniform vec2 uPic;
in vec2 vUv;
out vec4 oColor;
float sstep(float e0, float e1, float x) {
    float t = clamp((x - e0) / (e1 - e0), 0.0, 1.0);
    return t * t * (3.0 - 2.0 * t);
}
vec3 unpackRgb(float pk) {
    pk = floor(clamp(pk, 0.0, 16777215.0) + 0.5);
    return vec3(mod(floor(pk / 65536.0), 256.0), mod(floor(pk / 256.0), 256.0), mod(pk, 256.0)) / 255.0;
}
vec2 unpackPoint(float pk) {
    pk = floor(clamp(pk, 0.0, 16777215.0) + 0.5);
    return vec2(mod(floor(pk / 4096.0), 4096.0) - 2048.0, mod(pk, 4096.0) - 2048.0);
}
vec3 toYuv(vec3 c) {
    mat3 m = mat3(0.299, 0.587, 0.114, -0.14713, -0.28886, 0.436, 0.615, -0.51499, -0.10001);
    return c * m;
}
vec3 toRgb(vec3 c) {
    mat3 m = mat3(1.0, 0.0, 1.13983, 1.0, -0.39465, -0.58060, 1.0, 2.03211, 0.0);
    return c * m;
}
vec3 rgb2hsv(vec3 c) {
    vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
    vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
    vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
    float d = q.x - min(q.w, q.y);
    float e = 1.0e-10;
    return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
}
vec3 hsv2rgb(vec3 c) {
    vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
    vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
    return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
}
vec4 blendFull(vec4 tex, vec4 factor, float bright, int mode) {
    float ta = max(tex.a, 0.0001);
    vec4 F = factor * bright;
    vec4 o = vec4(0.0);
    if (mode == 0) {
        o = (tex * (1.0 - F.a) + F) * tex.a;
    } else if (mode == 1) {
        vec4 c = F + vec4(1.0) * (1.0 - F.a);
        o = vec4((tex.rgb / ta) * c.rgb, 1.0) * tex.a;
    } else if (mode == 2) {
        o = vec4((1.0 - (1.0 - tex.rgb / ta) * (1.0 - F.rgb)) * tex.a, tex.a);
    } else if (mode == 3) {
        vec3 res;
        for (int k = 0; k < 3; k++) {
            float t = sstep(0.5, 0.5, tex[k]);
            res[k] = t * (1.0 - (1.0 - 2.0 * (tex[k] - 0.5)) * (1.0 - factor[k])) +
                     (1.0 - t) * ((2.0 * tex[k]) * factor[k]);
        }
        o = vec4(res * tex.a, tex.a * tex.a);
    } else if (mode == 4) {
        vec3 ty = toYuv(tex.rgb);
        vec3 fy = toYuv(factor.rgb);
        o = vec4(toRgb(vec3(ty.x, fy.y, fy.z)) * tex.a, tex.a * tex.a);
    } else if (mode == 5) {
        o = max(F, tex) * tex.a;
    } else if (mode == 6) {
        o = (tex / (vec4(1.0) - F)) * tex.a;
    } else if (mode == 7) {
        o = (vec4(1.0) - (vec4(1.0) - tex) / F) * tex.a;
    } else if (mode == 8) {
        o = (tex + F) * tex.a;
    } else if (mode == 9) {
        o = (tex + F - vec4(1.0)) * tex.a;
    } else if (mode == 10) {
        vec3 res;
        for (int k = 0; k < 3; k++) {
            float t = sstep(0.5, 0.5, factor[k]);
            res[k] = t * (tex[k] + 2.0 * (factor[k] - 0.5)) +
                     (1.0 - t) * (tex[k] + 2.0 * factor[k] - 1.0);
        }
        o = vec4(res * tex.a, tex.a * tex.a);
    } else if (mode == 11) {
        o = min(F, tex) * tex.a;
    } else if (mode == 12 || mode == 13) {
        vec3 W = vec3(0.2126, 0.7152, 0.0722);
        float fl = length(W * F.rgb);
        float tl = length(W * tex.rgb);
        bool pickTex = mode == 12 ? (fl > tl) : (fl <= tl);
        o = vec4((pickTex ? tex.rgb : F.rgb) * tex.a, tex.a * tex.a);
    } else if (mode == 14 || mode == 15) {
        vec3 res;
        for (int k = 0; k < 3; k++) {
            float g = mode == 15 ? tex[k] : factor[k];
            float t = sstep(0.5, 0.5, g);
            res[k] = t * (1.0 - (1.0 - tex[k]) * (1.0 - (factor[k] - 0.5))) +
                     (1.0 - t) * (tex[k] * (factor[k] + 0.5));
        }
        o = vec4(res * tex.a, tex.a * tex.a);
    } else if (mode == 16) {
        vec3 res;
        for (int k = 0; k < 3; k++) {
            float t = sstep(0.5, 0.5, F[k]);
            res[k] = t * (1.0 - (1.0 - tex[k]) * (1.0 - 2.0 * (F[k] - 0.5))) +
                     (1.0 - t) * (tex[k] * (2.0 * F[k]));
        }
        o = vec4(res * tex.a, tex.a * tex.a);
    } else if (mode == 17) {
        vec3 fh = rgb2hsv(F.rgb);
        vec3 th = rgb2hsv(tex.rgb);
        o = vec4(hsv2rgb(vec3(fh.x, th.y, th.z)) * tex.a, tex.a * tex.a);
    } else if (mode == 18) {
        o = vec4(abs(tex.rgb * tex.a - F.rgb), tex.a);
    } else if (mode == 19) {
        vec3 ty = toYuv(tex.rgb);
        vec3 fy = toYuv(F.rgb);
        o = vec4(toRgb(vec3(fy.x, ty.y, ty.z)) * tex.a, tex.a * tex.a);
    } else if (mode == 20) {
        vec3 fh = rgb2hsv(F.rgb);
        vec3 th = rgb2hsv(tex.rgb);
        o = vec4(hsv2rgb(vec3(th.x, fh.y, th.z)) * tex.a, tex.a * tex.a);
    } else if (mode == 21) {
        vec3 res;
        for (int k = 0; k < 3; k++) {
            float t = sstep(0.5, 0.5, F[k]);
            res[k] = t * (1.0 - (1.0 - tex[k]) * (2.0 * (F[k] - 0.5))) +
                     (1.0 - t) * (tex[k] * (1.0 - 2.0 * F[k]));
        }
        o = vec4(res * tex.a, tex.a * tex.a);
    } else if (mode == 22) {
        vec3 res;
        for (int k = 0; k < 3; k++) {
            float t = sstep(0.5, 0.5, F[k]);
            res[k] = t * max(tex[k], 2.0 * (F[k] - 0.5)) +
                     (1.0 - t) * min(tex[k], 2.0 * F[k]);
        }
        o = vec4(res * tex.a, tex.a * tex.a);
    } else if (mode == 23) {
        o = (tex / F) * tex.a;
    } else if (mode == 24) {
        o = (tex - F) * tex.a;
    }
    return o;
}
vec4 blendShort(vec4 tex, vec4 factor, float bright, int mode) {
    float ta = max(tex.a, 0.0001);
    vec4 o = vec4(0.0);
    if (mode == 0) {
        o = factor * bright * tex.a;
    } else if (mode == 1) {
        vec4 F = factor * bright;
        vec4 c = F + vec4(1.0) * (1.0 - F.a);
        o = vec4((tex.rgb / ta) * c.rgb, 1.0) * tex.a;
    } else if (mode == 2) {
        vec4 F = factor * bright;
        o = vec4((1.0 - (1.0 - tex.rgb / ta) * (1.0 - F.rgb)) * tex.a, tex.a);
    } else if (mode == 3) {
        vec4 F = factor * bright;
        o = (tex * (1.0 - F.a) + F) * bright * tex.a;
    }
    return o;
}
vec2 centred(float scale, float angleDeg, vec2 pos, vec2 stretch, bool doubleScale) {
    vec2 p = vec2(vUv.x - 0.5, 0.5 - vUv.y);
    p.y *= uPic.y / uPic.x;
    p /= scale;
    float rad = angleDeg * 0.0174533;
    float cs = cos(rad), sn = sin(rad);
    p = vec2(p.x * cs + p.y * sn, -p.x * sn + p.y * cs);
    p += pos * vec2(-1.0, 1.0) / 500.0;
    if (doubleScale) p /= scale * stretch;
    return p;
}
vec2 hash22(vec2 p) {
    return fract(sin(vec2(dot(p, vec2(2127.1, 81.17)), dot(p, vec2(1269.5, 283.37)))) * 43758.5453);
}
float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    float top = mix(dot(-1.0 + 2.0 * hash22(i + vec2(0.0, 0.0)), f - vec2(0.0, 0.0)),
                    dot(-1.0 + 2.0 * hash22(i + vec2(1.0, 0.0)), f - vec2(1.0, 0.0)), u.x);
    float bot = mix(dot(-1.0 + 2.0 * hash22(i + vec2(0.0, 1.0)), f - vec2(0.0, 1.0)),
                    dot(-1.0 + 2.0 * hash22(i + vec2(1.0, 1.0)), f - vec2(1.0, 1.0)), u.x);
    return 0.5 + 0.5 * mix(top, bot, u.y);
}
void main() {
    vec4 tex = texture(uTex, vUv);
    vec3 W = vec3(0.2126, 0.7152, 0.0722);
    if (uKind == 0) {
        float rad = uP[3] * 0.0174533;
        vec2 v = normalize(vec2(cos(rad), -sin(rad)));
        float p = dot(vUv - 0.5, v) + 0.5;
        float feather = uP[2] / 100.0;
        float band = sstep(uP[0] - feather, uP[0], p) * (1.0 - sstep(uP[1], uP[1] + feather, p));
        float rs = band * tex.a * uQ[1];
        vec4 factor = vec4(unpackRgb(uP[4]) * rs, rs * uP[5]);
        vec4 result = blendFull(tex, factor, uP[6], int(uQ[0] + 0.5));
        oColor = mix(factor, result, uQ[2]) * tex.a;
        return;
    }
    if (uKind == 1) {
        vec2 pos = unpackPoint(uP[2]);
        vec2 stretch = unpackPoint(uQ[6]);
        vec2 uv = vUv / uP[0];
        float rad = uP[1] * 0.0174533;
        float cs = cos(rad), sn = sin(rad);
        uv = vec2(uv.x * cs + uv.y * sn, -uv.x * sn + uv.y * cs);
        uv += pos * vec2(-1.0, 1.0) / 500.0;
        uv /= uP[0] * stretch;
        float iTime = uP[3];
        vec2 tuv = uv - 0.5;
        float degree = noise(vec2(iTime * 0.1, tuv.x * tuv.y));
        float ratio = uPic.x / uPic.y;
        tuv.y *= 1.0 / ratio;
        float dr = radians((degree - 0.5) * 720.0 + 180.0);
        float dcs = cos(dr), dsn = sin(dr);
        tuv = vec2(tuv.x * dcs + tuv.y * dsn, -tuv.x * dsn + tuv.y * dcs);
        tuv.y *= ratio;
        tuv.x += sin(tuv.y * uQ[4] + iTime) / uQ[5];
        tuv.y += sin(tuv.x * uQ[4] * 1.5 + iTime) / (uQ[5] * 0.5);
        float mr = radians(-5.0);
        float rotX = tuv.x * cos(mr) + tuv.y * sin(mr);
        float sm = sstep(-0.3, 0.2, rotX);
        vec3 layer1 = mix(unpackRgb(uQ[0]), unpackRgb(uQ[1]), sm);
        vec3 layer2 = mix(unpackRgb(uQ[2]), unpackRgb(uQ[3]), sm);
        vec4 factor = vec4(mix(layer1, layer2, sstep(0.5, -0.3, tuv.y)), 1.0);
        vec4 result = blendShort(tex, factor, uP[5], int(uP[6] + 0.5));
        oColor = mix(tex, result, uR[0]) * tex.a;
        return;
    }
    if (uKind == 2) {
        vec2 p = centred(uQ[1], uQ[2], unpackPoint(uQ[3]), unpackPoint(uQ[4]), true);
        p.x *= uPic.x / uPic.y;
        float lt = uQ[0];
        float speed = 5.2 + sstep(0.8, 12.0, 1.0);
        float angle0 = atan(p.y, p.x) / (2.0 * 3.14159265359);
        float sh = fract(angle0 + 0.0002 * lt);
        float rad = 0.4 * length(p);
        float aF = sh * uP[4];
        float aStep = floor(aF);
        aF = fract(aF);
        float aRZ = 10.0 * fract(aStep * fract(aStep * 0.7535) * 45.1);
        float aSD = fract(aStep * fract(aStep * 0.82657) * 13.724);
        float t = speed * lt - aRZ;
        float angleDist = aSD + 0.1;
        float adist = angleDist / rad * uP[5];
        float dist = abs(fract(t * 0.1 + adist) - 0.5);
        float white1 = max(0.0, 1.0 - dist * 100.0 / (uP[6] * speed + adist));
        float white2 = max(0.0, (0.5 - 0.5 * cos(2.0 * 3.14159265359 * aF)) /
                                max(0.6, 2.0 * adist * angleDist));
        float white = white1 * white2;
        vec4 factor = mix(vec4(unpackRgb(uP[2]), uP[3]), vec4(unpackRgb(uP[0]), uP[1]), white);
        vec4 result = blendShort(tex, factor, uQ[5], int(uQ[6] + 0.5));
        oColor = mix(tex, result, uR[0]);
        return;
    }
    if (uKind == 3) {
        vec2 fs = vec2(uP[0]) / uPic;
        float w = dot(texture(uTex, vUv + vec2(-fs.x, 0.0)).rgb, W);
        float e = dot(texture(uTex, vUv + vec2(fs.x, 0.0)).rgb, W);
        float n = dot(texture(uTex, vUv + vec2(0.0, fs.y)).rgb, W);
        float so = dot(texture(uTex, vUv + vec2(0.0, -fs.y)).rgb, W);
        float nw = dot(texture(uTex, vUv + vec2(-fs.x, fs.y)).rgb, W);
        float ne = dot(texture(uTex, vUv + vec2(fs.x, fs.y)).rgb, W);
        float sw = dot(texture(uTex, vUv + vec2(-fs.x, -fs.y)).rgb, W);
        float se = dot(texture(uTex, vUv + vec2(fs.x, -fs.y)).rgb, W);
        float v = nw * 3.0 + n * 10.0 + ne * 3.0 + sw * -3.0 + so * -10.0 + se * -3.0;
        float h = nw * -3.0 + w * -10.0 + sw * -3.0 + ne * 3.0 + e * 10.0 + se * 3.0;
        if (uQ[2] > 0.5) { v *= v; h *= h; }
        v *= uP[2];
        h *= uP[2];
        float edge = sqrt(h * h + v * v);
        float g = clamp(edge - uP[1], 0.0, 1.0);
        float elR = clamp(edge - uP[1], 0.0, 1.0) / (1.0 - uP[1]);
        vec4 edgeNew = vec4(vec3(1.0 - elR), 1.0) * tex.a;
        float luminance = dot(edgeNew.rgb, W);
        vec4 edgeFinal = vec4(mix(unpackRgb(uP[3]), unpackRgb(uP[5]), luminance), mix(uP[4], uP[6], luminance)) * edgeNew.a;
        vec4 newFinal, oldFinal;
        if (uQ[4] > 0.5) {
            newFinal = vec4(1.0 - edgeFinal.rgb, edgeFinal.a) * tex.a;
            oldFinal = vec4(vec3(g) * tex.rgb, tex.a);
        } else {
            newFinal = edgeFinal * tex.a;
            oldFinal = vec4(vec3(1.0 - g) * tex.a, tex.a);
        }
        vec4 factor = uQ[3] > 0.5 ? newFinal : oldFinal;
        int mode = int(uQ[1] + 0.5);
        vec4 result = vec4(0.0);
        float ta = max(tex.a, 0.0001);
        if (mode == 0) {
            result = factor * tex.a;
        } else if (mode == 1) {
            vec4 c = factor + vec4(1.0) * (1.0 - factor.a);
            result = vec4((tex.rgb / ta) * c.rgb, 1.0) * tex.a;
        } else if (mode == 2) {
            result = vec4((1.0 - (1.0 - tex.rgb / ta) * (1.0 - factor.rgb)) * tex.a, tex.a);
        }
        oColor = mix(tex, result, uQ[0]);
        return;
    }
    if (uKind == 4) {
        vec2 r = centred(uP[0], uP[1], unpackPoint(uP[2]), unpackPoint(uQ[0]), true);
        float col = sin(r.y + r.x * uP[5] - uP[3] * 9.0) * uP[6];
        col *= col * col * 0.6;
        col = clamp(col, 0.0, 1.0);
        vec4 frag = vec4(vec3(2.0 * col), col);
        float luminance = dot(frag.rgb, W);
        vec4 factor = vec4(mix(unpackRgb(uQ[1]), unpackRgb(uQ[3]), luminance),
                           mix(uQ[2], uQ[4], luminance)) * frag.a;
        vec4 result = blendShort(tex, factor, uQ[5], int(uQ[6] + 0.5));
        oColor = mix(tex, result, uR[0]) * tex.a;
        return;
    }
    vec2 p = centred(uQ[2], uQ[3], vec2(uQ[1] - 250.0, 0.0), vec2(1.0), false);
    vec4 c1 = vec4(unpackRgb(uP[0]), uP[1]);
    vec4 c2 = vec4(unpackRgb(uP[2]), uP[3]);
    vec4 c3 = vec4(unpackRgb(uP[4]), uP[5]);
    vec4 left = mix(c1, c2, p.x / uP[6]);
    vec4 right = mix(c2, c3, (p.x - uQ[0]) / 0.5);
    vec4 factor = mix(left, right, step(0.5, p.x));
    vec4 result = blendFull(tex, factor, uQ[4], int(uQ[5] + 0.5));
    vec4 xn = mix(factor, result, uR[0]) * tex.a;
    oColor = mix(tex, xn, uQ[6]) * tex.a;
}
)";

const char* kDeepGlow1Comp = R"(#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform sampler2D uBlur;
uniform sampler2D uHblur;
uniform float uP[7];
uniform float uQ[7];
uniform float uR[7];
uniform vec2 uOff;
in vec2 vUv;
out vec4 oColor;
float sstep(float e0, float e1, float x) {
    float t = clamp((x - e0) / (e1 - e0), 0.0, 1.0);
    return t * t * (3.0 - 2.0 * t);
}
vec3 unpackRgb(float pk) {
    pk = floor(clamp(pk, 0.0, 16777215.0) + 0.5);
    return vec3(mod(floor(pk / 65536.0), 256.0), mod(floor(pk / 256.0), 256.0), mod(pk, 256.0)) / 255.0;
}
vec3 toYuv(vec3 c) {
    mat3 m = mat3(0.299, 0.587, 0.114, -0.14713, -0.28886, 0.436, 0.615, -0.51499, -0.10001);
    return c * m;
}
vec3 toRgb(vec3 c) {
    mat3 m = mat3(1.0, 0.0, 1.13983, 1.0, -0.39465, -0.58060, 1.0, 2.03211, 0.0);
    return c * m;
}
void main() {
    vec4 base = texture(uTex, vUv) * uP[4];
    vec4 comp = texture(uBlur, vUv + uOff);
    vec4 hblur = texture(uHblur, vUv);
    vec3 yuv = toYuv(comp.rgb);
    float rad = uP[2] * 0.0174533;
    float cs = cos(rad), sn = sin(rad);
    vec2 huv = vec2(yuv.y * cs + yuv.z * sn, -yuv.y * sn + yuv.z * cs);
    float origSat = min(1.0, length(huv) * 2.5);
    vec2 suv = huv * uQ[3];
    huv = suv + (huv - suv) * origSat;
    huv *= (uQ[2] + 1.0);
    vec3 nc = toRgb(vec3(yuv.x, huv));
    float shA = comp.a * uQ[1] * comp.a;
    vec3 crgb = vec3(1.0 - shA);
    float rr = length(vec2(uP[1]) - vUv);
    float ca = max(comp.a, 0.0001);
    vec4 dbl = vec4(nc / ca, 1.0) * (rr - 0.5);
    int flags = int(uR[3] + 0.5);
    if ((flags & 4) != 0) {
        float luma = dot(dbl.rgb, vec3(0.2126, 0.7152, 0.0722));
        float feather = uR[2];
        float lowB = uR[0] * (1.0 + feather) - 0.0001;
        float lowA = lowB - max(feather, 0.00001);
        float highA = uR[1] * (1.0 + feather) - feather + 0.0001;
        float highB = highA + max(feather, 0.00001);
        float lumafx = min(sstep(lowA, lowB, luma), 1.0 - sstep(highA, highB, luma));
        dbl *= lumafx;
    }
    vec4 blendC = vec4(dbl.rgb * crgb, dbl.a);
    vec4 rfx = vec4(unpackRgb(uP[6]), 1.0) * dbl.a;
    rfx.a = dbl.a * dbl.a;
    vec4 rrfinal = blendC;
    if ((flags & 2) != 0) rrfinal = mix(blendC, rfx, uP[5]) * dbl.a;
    vec4 L2 = (base * uQ[0] + rrfinal * uP[3]) * comp.a + base;
    if ((flags & 1) != 0) {
        vec4 l1 = vec4(0.0, 0.0, 0.0, hblur.a * uQ[6]);
        oColor = l1 * (1.0 - L2.a) + L2;
    } else {
        oColor = L2;
    }
}
)";

// Displace Map (Alight Motion's displacemap3 = displacemap2, and displacemaprad; their
// recovered shaders): the layer's own channels displace where the BACKDROP (the frame so
// far, texBackdrop_ — the def's `comp`, srcType "comp", i.e. everything below the layer,
// never the layer itself) is sampled. uRadial 0: p = [offs packed, typex, typey, edges,
// fromctr, invert], the offset in screen norm. uRadial 1: p = [angle deg, radius, typex,
// typey, edges, fromctr, invert], q = [centre packed, ...], a rotation + radial shift
// around the centre point; acScreenSize is the canvas size (uScreen). The edges folds are
// the def's own: 0 repeat = the raw sample, 1 mirror = 1 - abs(mod(s, 2) - 1), 2 = vec4(0)
// outside [0,1]^2, 3 tile = mod(s, 1); every mode but 0 scales by the pixel's alpha.
// fxDisplaceMapAmPick / fxDisplaceMapAmShape / fxDisplaceMapAmUv / fxDisplaceMapAmEdge /
// fxDisplaceMapRadAmUv in fx_plan.cpp are the CPU twins.
const char* kDisplaceMap = R"(#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform sampler2D uBg;
uniform vec4 uRect;
uniform float uP[7];
uniform float uQ[7];
uniform int uRadial;
uniform vec2 uScreen;
uniform float uLayerAspect;
in vec2 vUv;
out vec4 oColor;
vec2 amUnpackPoint(float pk) {
    pk = floor(clamp(pk, 0.0, 16777215.0) + 0.5);
    return vec2(mod(floor(pk / 4096.0), 4096.0) - 2048.0, mod(pk, 4096.0) - 2048.0);
}
float pickCh(vec4 t, float lum, float type) {
    return type < 0.5 ? lum : type < 1.5 ? t.r : type < 2.5 ? t.g : t.b;
}
void main() {
    vec4 tex = texture(uTex, vUv);
    vec2 sn = uRect.xy + vUv * uRect.zw;
    float lum = dot(tex.rgb, vec3(0.2126, 0.7152, 0.0722));
    float typex = uP[2];
    float typey = uP[3];
    float edges = uP[4];
    float fromctr = uP[5];
    float invert = uP[6];
    float px = pickCh(tex, lum, typex);
    float py = pickCh(tex, lum, typey);
    if (fromctr > 0.5) { px = (px - 0.5) * 2.0; py = (py - 0.5) * 2.0; }
    if (invert > 0.5) { px = 1.0 - px; py = 1.0 - py; }
    vec2 spos;
    if (uRadial == 1) {
        vec2 lc = uRect.xy + 0.5 * uRect.zw;
        vec2 cen = vec2(uQ[0], uQ[1]) / 1000.0 * vec2(-1.0, 1.0);
        vec2 anchor = lc - cen;
        vec2 uv = sn - anchor;
        float asp = uScreen.x > 0.0 ? uScreen.y / uScreen.x : 1.0;
        uv.y *= asp;
        vec2 adj = vec2(uP[0] * px, uP[1] * py) * tex.a;
        float ca = cos(adj.x * 0.0174533), sa = sin(adj.x * 0.0174533);
        uv = vec2(ca * uv.x + sa * uv.y, -sa * uv.x + ca * uv.y);
        float rl = length(uv);
        vec2 rdir = rl > 0.000001 ? uv / rl : vec2(0.0);
        uv += rdir * (adj.y / max(length(uScreen), 1.0));
        uv.y /= asp;
        spos = uv + anchor;
    } else {
        vec2 adj = vec2(uP[0], uP[1]) / 1000.0;
        adj.x *= uLayerAspect * -1.0;
        adj *= vec2(px, py) * tex.a;
        spos = sn + adj;
    }
    if (edges < 0.5) {
        oColor = texture(uBg, spos);
    } else if (edges < 1.5) {
        spos = vec2(1.0) - abs(mod(spos, vec2(2.0)) - vec2(1.0));
        oColor = texture(uBg, spos) * tex.a;
    } else if (edges < 2.5) {
        if (spos.x < 0.0 || spos.x > 1.0 || spos.y < 0.0 || spos.y > 1.0) {
            oColor = vec4(0.0);
        } else {
            oColor = texture(uBg, spos) * tex.a;
        }
    } else if (edges < 3.5) {
        oColor = texture(uBg, mod(spos, vec2(1.0))) * tex.a;
    } else {
        oColor = texture(uBg, spos) * tex.a;
    }
}
)";

// Smooth Bevel (Alight Motion's smoothbevel, its recovered shader): pass 0 scans each
// row for the nearest transparent pixel left / right and packs the distances into an
// RGBA buffer (the def's encodeFloat of distance / MAX_RADIUS); pass 1 scans columns of
// that buffer for the four corner distances, smoothsteps them by uRadius2 and shades
// with the two lights. uP in pass 1: [angle_s deg, angle_d deg, elev_s, elev_d, spec,
// intensity_s, intensity_d]; uColS / uColD are the two colours (straight; AM's colour
// alpha is dropped like every packed colour in this engine). The geometry runs in the
// layer's own norm here (AM uses its screen norm; the def's sizes come from the input
// image, getTexSize(inputImg.size), as here). fxSmoothBevelAmEncode /
// fxSmoothBevelAmDecode / fxSmoothBevelAmLights in fx_plan.cpp are the CPU twins.
const char* kSmoothBevel = R"(#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform sampler2D uBuf;
uniform vec2 uTexel;
uniform float uRadius2;
uniform int uPass;
uniform float uP[7];
uniform vec3 uColS;
uniform vec3 uColD;
in vec2 vUv;
out vec4 oColor;
vec2 encodeFloat(float v) {
    vec2 enc = fract(vec2(1.0, 255.0) * v);
    enc.x -= enc.y / 255.0;
    return enc;
}
float decodeFloat(vec2 v) { return dot(v, vec2(1.0, 1.0 / 255.0)); }
void main() {
    if (uPass == 0) {
        int steps = max(1, int(min(uRadius2, 2048.0)));
        float nearestLeft = 16383.0;
        float nearestRight = 16383.0;
        for (int i = -2048; i < 0; i++) {
            if (i < -steps) continue;
            float x = float(i);
            vec4 s = texture(uTex, vec2(vUv.x + x * uTexel.x, vUv.y));
            float dist = s.a < 0.003 ? abs(x) : uRadius2 * 2.0;
            nearestLeft = min(nearestLeft, dist);
        }
        for (int i = 0; i < 2048; i++) {
            if (i >= steps) break;
            float x = float(i);
            vec4 s = texture(uTex, vec2(vUv.x + x * uTexel.x, vUv.y));
            float dist = s.a < 0.003 ? abs(x) : uRadius2 * 2.0;
            nearestRight = min(nearestRight, dist);
        }
        oColor = vec4(encodeFloat(nearestLeft / 16383.0), encodeFloat(nearestRight / 16383.0));
        return;
    }
    int steps = max(1, int(min(uRadius2, 2048.0)));
    float nTL = 16383.0, nTR = 16383.0, nBL = 16383.0, nBR = 16383.0;
    for (int i = -2048; i < 0; i++) {
        if (i < -steps) continue;
        float y = float(i);
        vec4 s = texture(uBuf, vec2(vUv.x, vUv.y + y * uTexel.y));
        float nl = decodeFloat(s.rg) * 16383.0;
        float nr = decodeFloat(s.ba) * 16383.0;
        float yd = abs(y);
        nBL = min(nBL, length(vec2(nl, yd)));
        nBR = min(nBR, length(vec2(nr, yd)));
    }
    for (int i = 0; i < 2048; i++) {
        if (i >= steps) break;
        float y = float(i);
        vec4 s = texture(uBuf, vec2(vUv.x, vUv.y + y * uTexel.y));
        float nl = decodeFloat(s.rg) * 16383.0;
        float nr = decodeFloat(s.ba) * 16383.0;
        float yd = abs(y);
        nTL = min(nTL, length(vec2(nl, yd)));
        nTR = min(nTR, length(vec2(nr, yd)));
    }
    float r2 = max(uRadius2, 0.0001);
    float cTL = smoothstep(0.0, 1.0, nTL / r2);
    float cTR = smoothstep(0.0, 1.0, nTR / r2);
    float cBL = smoothstep(0.0, 1.0, nBL / r2);
    float cBR = smoothstep(0.0, 1.0, nBR / r2);
    vec3 v = (vec3(-1.0, -1.0, -1.0) * cBL + vec3(1.0, -1.0, -1.0) * cBR
            + vec3(-1.0, 1.0, -1.0) * cTL + vec3(1.0, 1.0, -1.0) * cTR) / 4.0;
    float vl = length(v);
    vec3 nv = vl > 0.000001 ? v / vl : vec3(0.0, 0.0, -1.0);
    float as = uP[0] * 0.0174533, ad = uP[1] * 0.0174533;
    vec3 lightS = normalize(vec3(cos(as), -sin(as), -uP[2]));
    vec3 lightD = normalize(vec3(cos(ad), -sin(ad), -uP[3]));
    float lumS = pow(max(dot(nv, lightS), 0.0), uP[4]);
    float lumD = dot(nv, lightD);
    vec4 orig = texture(uTex, vUv);
    vec4 litD = vec4(uColD, 1.0) * lumD * uP[6] * orig.a;
    vec4 litS = vec4(uColS, 1.0) * lumS * uP[5] * orig.a;
    vec4 result = orig;
    result = litD + result * (1.0 - litD.a);
    result = litS + result * (1.0 - litS.a);
    oColor = result;
}
)";

// Light Glow (Alight Motion's lightglow, its recovered shader — all four passes share
// it there, branched on acPass, and do here): pass 0 keeps pixels over the luminance
// threshold, mixed toward the tint colour, into a half-size buffer; passes 1 / 2 are
// the def's incremental-Gaussian blurs (horizontal on the pick, vertical on that, the
// second pass compositing the screen blend + halo over the base); pass 3 copies the
// result out. uKernel is fxLightGlowAmKernel (strength and the CANVAS size — AM's
// acPreviewSize), computed on the CPU. The def's loop steps pairs of taps to
// numBlurPixelsPerSide; capped at 4096 here (a strength beyond ~78 at 1080p — the
// corpus instances sit far below). fxLightGlowAmPick / fxLightGlowAmComposite in
// fx_plan.cpp are the CPU twins.
const char* kLightGlow = R"(#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform sampler2D uBlur;
uniform vec2 uTexel;
uniform int uPass;
uniform float uP[7];
uniform vec3 uColor;
in vec2 vUv;
out vec4 oColor;
vec4 composite(vec4 a, vec4 b) { return a * (1.0 - b.a) + b; }
vec4 pick(vec4 c) {
    float lum = dot(c.rgb, vec3(0.2126, 0.7152, 0.0722));
    return lum > uP[1] ? mix(c, vec4(uColor, 1.0) * c.a, uP[4]) : vec4(0.0);
}
vec4 blurInternal(vec2 texOffset) {
    if (uP[0] <= 0.0) return vec4(0.0);
    float numBlur = uP[6];
    float adjSigma = numBlur / 2.14596602;
    vec3 inc;
    inc.x = 1.0 / (sqrt(2.0 * 3.14159265) * adjSigma);
    inc.y = exp(-0.5 / (adjSigma * adjSigma));
    inc.z = inc.y * inc.y;
    vec4 avg = texture(uBlur, vUv) * inc.x;
    float coefSum = inc.x;
    for (float i = 1.0; i <= 4096.0; i += 2.0) {
        if (i > numBlur) break;
        float offset0 = i;
        float offset1 = i + 1.0;
        inc.xy *= inc.yz;
        coefSum += 2.0 * inc.x;
        float weight0 = inc.x;
        inc.xy *= inc.yz;
        coefSum += 2.0 * inc.x;
        float weight1 = inc.x;
        float weightL = weight0 + weight1;
        float offsetL = (offset0 * weight0 + offset1 * weight1) / weightL;
        vec2 stL = clamp(vUv - offsetL * texOffset, vec2(0.0), vec2(1.0));
        vec2 stR = clamp(vUv + offsetL * texOffset, vec2(0.0), vec2(1.0));
        avg += texture(uBlur, stL) * weightL;
        avg += texture(uBlur, stR) * weightL;
    }
    return avg / coefSum;
}
void main() {
    if (uPass == 0) {
        oColor = pick(texture(uTex, vUv));
    } else if (uPass == 1) {
        oColor = blurInternal(vec2(uTexel.x, 0.0));
    } else if (uPass == 2) {
        vec4 base = texture(uTex, vUv);
        vec4 result = uP[0] <= 0.0 ? pick(base) : blurInternal(vec2(0.0, uTexel.y));
        vec3 aa = result.rgb / max(result.a, 0.0001) * uP[2];
        float h = (1.0 - smoothstep(0.0, 0.15, abs(result.a - 0.15))) * uP[3];
        vec3 hh = mix(aa / 0.15 * uP[3], uColor * uP[3], uP[4]);
        aa += hh * h;
        vec3 bb = base.rgb / max(base.a, 0.0001);
        vec3 rgb = 1.0 - (1.0 - aa) * (1.0 - bb);
        oColor = composite(base, vec4(rgb, 1.0) * min(base.a, result.a) * uP[5]);
    } else {
        oColor = texture(uTex, vUv);
    }
}
)";


// Inner Glow (Alight Motion's inner-glow, its recovered shader): pass 0 scans each row
// for the nearest opaque / transparent pixel and packs both X distances into an RGBA
// buffer (the def's encodeFloat of distance / MAX_RADIUS, the Smooth Bevel encoding);
// pass 1 scans columns of that buffer for the true distance to the nearest transparent
// pixel and blends the glow colour over the layer by alpha * p, p the def's REVERSED
// smoothstep of that distance (formula form, like fxSstep). The taps step by uStride
// pixels (the def's q), so the tap count stays <= 40 at any radius. uP: [alpha,
// hardness, blend mode]. The geometry runs in the layer's own norm (AM uses its screen
// norm), like Smooth Bevel's port. fxInnerGlowAmStride / fxInnerGlowAmP /
// fxInnerGlowAmMap in fx_plan.cpp are the CPU twins.
const char* kInnerGlow = R"(#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform sampler2D uBuf;
uniform vec2 uTexel;
uniform float uRadius2;
uniform float uStride;
uniform int uPass;
uniform float uP[7];
uniform vec3 uColor;
in vec2 vUv;
out vec4 oColor;
vec2 encF(float v) {
    vec2 enc = fract(vec2(1.0, 255.0) * v);
    enc.x -= enc.y / 255.0;
    return enc;
}
float decF(vec2 v) { return dot(v, vec2(1.0, 1.0 / 255.0)); }
void main() {
    int steps = int(clamp(uRadius2 / max(uStride, 0.001), 1.0, 48.0));   // AM: max(1, int(radius2/q)), truncated
    if (uPass == 0) {
        float nearOut = 16383.0;
        float nearIn = 16383.0;
        for (int i = -48; i < 48; i++) {
            if (i < -steps || i >= steps) continue;
            float x = float(i) * uStride;
            vec4 t = texture(uTex, vUv + vec2(x * uTexel.x, 0.0));
            nearOut = min(nearOut, t.a > 0.0 ? abs(x) : uRadius2 * 2.0);
            nearIn = min(nearIn, t.a < 0.003 ? abs(x) : uRadius2 * 2.0);
        }
        oColor = vec4(encF(nearOut / 16383.0), encF(nearIn / 16383.0));
        return;
    }
    float nearIn = 16383.0;
    for (int i = -48; i < 48; i++) {
        if (i < -steps || i >= steps) continue;
        float y = float(i) * uStride;
        vec4 b = texture(uBuf, vUv + vec2(0.0, y * uTexel.y));
        float xIn = decF(b.ba) * 16383.0;
        nearIn = min(nearIn, length(vec2(xIn, abs(y))));
    }
    float e0 = uRadius2 + 0.51;
    float e1 = uP[1] * uRadius2 - 0.51;
    float t = clamp((nearIn - e0) / (e1 - e0), 0.0, 1.0);
    float p = t * t * (3.0 - 2.0 * t);
    vec4 tex = texture(uTex, vUv);
    float inv = 1.0 / max(tex.a, 0.0001);
    vec3 res;
    int bm = int(uP[2] + 0.5);
    if (bm == 1) {
        res = (tex.rgb * inv) * uColor;
    } else if (bm == 2) {
        res = 1.0 - (1.0 - tex.rgb * inv) * (1.0 - uColor);
    } else {
        res = uColor;
    }
    vec4 result = vec4(res, 1.0) * tex.a;
    oColor = mix(tex, result, clamp(uP[0], 0.0, 1.0) * p);
}
)";

// Extrude / Extrude Flat (Alight Motion's `s3d-extrude`, its recovered shader; fx_plan.cpp
// fxExtrudeAmPixel is the CPU twin): the layer's silhouette becomes a 3D box — a sphere-
// tracing march (up to MAX_ITER steps, the def's own cap of 800) over the box SDF, whose
// step size runs off the blurred copy's alpha (uBlur = the gaussianblur buffer pass:
// the layer blurred at the effect's `strength`, the name-matched pass wiring, read with
// the gaussianblur kernel (fxAmGaussianSigma)). uMat is the orientation matrix R = Rz*Rx*Ry
// (fxExtrudeRotateMat; the host `orient` is the identity — layers here have no 3D
// orientation, and the only serialised orient in the corpus is the identity quaternion).
// The maths runs in AM's layer space (layer-norm, y down); texture fetches flip y back
// to this engine's vUv. Extrude Flat (uBlend.w = 1) is the same march with the Flat
// shading branch and uBlur = the layer itself; its def was recovered in hunt round 3
// (defs-batch8) and build 136 aligned the flat branch to it parameter by parameter:
// its own ray spread (st / 6.0, not extrude's 5.8), its own step-size alpha factor
// (1.5 on the same-sample alpha, not 2.0 on a blur buffer), no strength term in the
// step size, and `inton` (carried in uBlend.z for flat ops) gating the interior /
// fade colour mixes. The def's texCoord projection step (its vecToVec rotation of
// the local coordinate) remains the one shared simplification of build 127's march:
// both defs carry it identically and the march here projects the local coordinate
// directly — recorded in ROADMAP Queue 15(g).
const char* kExtrude = R"(#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform sampler2D uBlur;
uniform vec2 uSize;
uniform mat3 uMat;
uniform vec3 uCenter;
uniform vec3 uBox;
uniform vec3 uMisc;
uniform vec4 uBlend;
uniform vec4 uInterior;
uniform vec4 uFade;
uniform vec4 uLightA;
uniform vec3 uLightColor;
uniform vec3 uLightPos;
in vec2 vUv;
out vec4 oColor;
vec4 texAt(vec2 tc) { return textureLod(uTex, vec2(tc.x, 1.0 - tc.y), 0.0); }
vec4 blurAt(vec2 tc) { return textureLod(uBlur, vec2(tc.x, 1.0 - tc.y), 0.0); }
float sdBox(vec3 p, vec3 r) {
    vec3 q = abs(p) - r;
    return length(max(q, 0.0)) + min(max(q.x, max(q.y, q.z)), 0.0);
}
vec2 sdExtrude(vec3 p) {
    vec2 st = (p.xy / uBox.xy + 1.0) / 2.0;
    vec4 texColor = texAt(st);
    vec4 blurColor = blurAt(st);
    float t = max(clamp(blurColor.a * (uBlend.w > 0.5 ? 1.5 : 2.0), 0.0, 1.0), texColor.a);
    float z = (p.z / uBox.z + 1.0) / 2.0;
    float maxdim = length(uBox);
    float td = (1.0 - t) * (maxdim * 2.0 * (1.0 + uMisc.y)) / uMisc.z;
    return vec2(max(td, sdBox(p, uBox * vec3(2.0, 2.0, 1.0))), z);
}
vec2 distD(vec3 wp) { return sdExtrude(uMat * (wp - uCenter)); }
float lighting(vec3 p, vec3 n) {
    return dot(n, normalize(uLightPos - p));
}
float directionalLighting(vec3 p, vec3 n) {
    return dot(n, normalize(uLightPos));
}
vec4 applyShading(vec4 texColor, vec3 p, vec3 n, float d) {
    float a = smoothstep(30.0, 9.0, d) * smoothstep(0.3, 0.2, n.z);
    int shadingType = uBlend.w > 0.5 ? 0 : int(uBlend.z + 0.5);
    if (shadingType == 0) {
        return texColor * a;
    } else if (shadingType == 1 || shadingType == 2) {
        float l = shadingType == 1 ? lighting(p, n) : directionalLighting(p, n);
        vec3 surface = texColor.rgb * uLightA.w + texColor.rgb * uLightColor * l * (1.0 - uLightA.w);
        surface *= uLightA.x;
        float s = pow(max(l, 0.0), uLightA.z);
        surface = mix(surface * (1.0 - uLightA.y * 0.1), vec3(1.0), uLightA.y * s);
        return vec4(surface, texColor.a) * a;
    } else if (shadingType == 3) {
        float ds = max(max(uBox.x, uBox.y), uBox.z) * 2.5;
        float dd = 1.0 - ((d - uCenter.z + ds / 2.0) / ds);
        return vec4(dd, dd, dd, 1.0) * a;
    } else if (shadingType == 4) {
        return vec4(n / 2.0 + 0.5, 1.0) * a;
    }
    float ds = max(max(uBox.x, uBox.y), uBox.z) * 2.5;
    float dd = 1.0 - ((d - uCenter.z + ds / 2.0) / ds);
    return vec4((n / 2.0 + 0.5) * dd, 1.0) * a;
}
void main() {
    vec2 layerNorm = vec2(vUv.x, 1.0 - vUv.y);
    vec2 st = layerNorm - 0.5;
    if (uSize.x < uSize.y) st.x *= uSize.x / uSize.y; else st.y *= uSize.y / uSize.x;
    st /= uMisc.x;
    st /= (uBlend.w > 0.5 ? 6.0 : 5.8);
    vec3 ro = vec3(0.0, 1.0, -1.0);
    vec3 rd = normalize(vec3(st, 1.0));
    float d = 0.0;
    float dz = -1.0;
    int iter = int(uMisc.z + 0.5);
    for (int i = 0; i < 800; i++) {
        if (i >= iter) break;
        vec3 p = ro + rd * d;
        vec2 dinf = distD(p);
        d += dinf.x;
        if (dinf.x < 1e-7 || d > 10000.0) { dz = dinf.y; break; }
    }
    vec3 p = ro + rd * d;
    float d0 = distD(p).x;
    vec3 nRaw = vec3(d0) - vec3(distD(p - vec3(0.01, 0.0, 0.0)).x,
                                distD(p - vec3(0.0, 0.01, 0.0)).x,
                                distD(p - vec3(0.0, 0.0, 0.01)).x);
    float nl = length(nRaw);
    vec3 n = nl > 1e-12 ? nRaw / nl : vec3(0.0);
    vec3 localCoord = uMat * (p - uCenter);
    vec2 texCoord = (localCoord.xy / uBox.xy + 1.0) / 2.0;
    float aMask = (uBox.x < 0.0105 || uBox.y < 0.0105) ? 0.0 : 1.0;
    vec4 baseColor = texAt(texCoord);
    vec4 blurColor = blurAt(texCoord);
    vec4 texColor;
    if (blurColor.a > baseColor.a && blurColor.a > 0.25) {
        texColor = blurColor / blurColor.a;
    } else if (baseColor.a > 0.25) {
        texColor = baseColor / baseColor.a;
    } else {
        texColor = baseColor;
    }
    if (dz > 0.001 && dz < 0.999 && (uBlend.w < 0.5 || uBlend.z > 0.5)) {
        texColor = mix(texColor, uInterior * texColor.a, uBlend.x);
        texColor = mix(texColor, uFade * texColor.a, uBlend.y * dz);
    }
    oColor = applyShading(texColor, p, n, d) * aMask;
}
)";

// ---- v175: Alight's effects tier 4 dedicated passes (defs-batch7; fx_plan.cpp carries
// the CPU twins) ----

// Channel Map (Alight's channelmap): un-premultiply, re-pick the channels (sources
// 0 R, 1 G, 2 B, 3 A, 4 luma, 5 zero, 6 one), re-premultiply by the NEW alpha. The
// def's maskFactor is 1 over the layer's own picture, which is exactly this target.
const char* kChannelMapAm = R"(#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform float uP[7];
in vec2 vUv;
out vec4 oColor;
float pickCh(int src, vec3 s, float a, float lum) {
    if (src == 0) return s.r;
    if (src == 1) return s.g;
    if (src == 2) return s.b;
    if (src == 3) return a;
    if (src == 4) return lum;
    if (src == 5) return 0.0;
    return 1.0;
}
void main() {
    vec4 t = texture(uTex, vUv);
    float lum = dot(t.rgb, vec3(0.2126, 0.7152, 0.0722));
    vec3 s = t.rgb;
    if (t.a > 0.0) s /= t.a;
    float na = pickCh(int(uP[3] + 0.5), s, t.a, lum);
    oColor = vec4(vec3(pickCh(int(uP[0] + 0.5), s, t.a, lum),
                       pickCh(int(uP[1] + 0.5), s, t.a, lum),
                       pickCh(int(uP[2] + 0.5), s, t.a, lum)) * na, na);
}
)";

// Mosaic (Alight's mosaic): pass 0 averages the input along x at the x-snapped
// coordinate into an lv_ level, pass 1 averages along y at the y-snapped coordinate —
// of the H buffer, except the base tap, which reads the INPUT (the def's
// inputImg / hblur split, verbatim). All coordinate maths runs in AM's layer space
// (y down), so texture reads flip y. uP: size, phase x, phase y; uSize: layer px.
const char* kMosaicAm = R"(#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform sampler2D uHbuf;
uniform int uPass;
uniform vec3 uP;
uniform vec2 uSize;
in vec2 vUv;
out vec4 oColor;
vec4 texAt(vec2 am) { return texture(uTex, vec2(am.x, 1.0 - am.y)); }
vec4 hAt(vec2 am) { return texture(uHbuf, vec2(am.x, 1.0 - am.y)); }
float snapC(float c, float adj, float dim) {
    float tile = uP.x / dim;
    float cc = c - 0.5;
    float m0 = cc + adj / dim;
    float m = m0 - tile * floor(m0 / tile);
    return cc - m + 0.5;
}
void main() {
    vec2 am = vec2(vUv.x, 1.0 - vUv.y);
    if (uPass == 0) {
        float sx = snapC(am.x, -uP.y, uSize.x);
        int n = int(clamp(uP.x / 2.0, 1.01, 100.01));
        vec4 acc = texAt(vec2(sx, am.y));
        for (int i = 1; i < 100; i++) {
            if (i >= n) break;
            float off = (uP.x / uSize.x) * (float(i) / float(n - 1) - 0.5);
            acc += texAt(vec2(sx - off, am.y));
        }
        oColor = acc / float(n);
        return;
    }
    float sy = snapC(am.y, uP.z, uSize.y);
    int n = int(clamp(uP.x, 1.01, 100.01));
    vec4 acc = texAt(vec2(am.x, sy));
    for (int i = 1; i < 100; i++) {
        if (i >= n) break;
        float off = (uP.x / uSize.y) * (float(i) / float(n - 1) - 0.5);
        acc += hAt(vec2(am.x, sy - off));
    }
    oColor = acc / float(n);
}
)";

// The box passes of AM's blur defs, one program:
//   uMode 0: boxblur2 (inner blur's host) — base tap + n-1 taps at -vel * (i/(n-1) - 0.5), / n.
//   uMode 1: the symmetric box (boxblur3 / feather) — base + pairs at +/- vel * i/(n-1), / (2n-1).
//   uMode 2: inner blur's composite — vec4(blur.rgb / blur.a, 1) * base.a.
//   uMode 3: feather's final — the symmetric V box over uTex, then base * (result.a)^2.
// uVel carries the pass velocity in norm units (its magnitude is the def's per-pass
// strength); both tap spans are symmetric, so the engine's y-up targets need no flip.
const char* kBoxPair = R"(#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform sampler2D uBase;
uniform vec2 uVel;
uniform int uSteps;
uniform int uMode;
in vec2 vUv;
out vec4 oColor;
void main() {
    if (uMode == 2) {
        vec4 b = texture(uTex, vUv);
        vec4 base = texture(uBase, vUv);
        oColor = vec4(b.rgb / max(b.a, 0.0001), 1.0) * base.a;
        return;
    }
    vec4 acc = texture(uTex, vUv);
    if (uMode == 0) {
        for (int i = 1; i < 100; i++) {
            if (i >= uSteps) break;
            float f = float(i) / float(uSteps - 1) - 0.5;
            acc += texture(uTex, vUv - uVel * f);
        }
        oColor = acc / float(uSteps);
        return;
    }
    for (int i = 1; i < 150; i++) {
        if (i >= uSteps) break;
        vec2 off = uVel * (float(i) / float(uSteps - 1));
        acc += texture(uTex, vUv + off) + texture(uTex, vUv - off);
    }
    vec4 r = acc / float(2 * uSteps - 1);
    if (uMode == 3) {
        vec4 base = texture(uBase, vUv);
        oColor = base * (r.a * r.a);
    } else {
        oColor = r;
    }
}
)";

// Glow (Alight's glow — the OUTER glow): Inner Glow's distance-scan machinery with
// both distances kept, and the composite inverted: pass 0 writes the X nearest
// distances (rg = nearest outside, a > 0; ba = nearest inside, a < 0.003), pass 1
// folds the Y scan in and adds the glow colour behind the original by (1 - tex.a).
// Blend modes 1 / 2 read the backdrop (uComp at the uRect-mapped position, the
// Copy Background idiom); the renderer falls back to mode 0 when no backdrop was
// captured. uP: [alpha, hardness, blend mode]; fxGlowAmMap is the CPU twin.
const char* kGlowOuter = R"(#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform sampler2D uBuf;
uniform sampler2D uComp;
uniform vec4 uRect;
uniform vec2 uTexel;
uniform float uRadius2;
uniform float uStride;
uniform int uPass;
uniform float uP[7];
uniform vec3 uColor;
uniform float uColorA;
in vec2 vUv;
out vec4 oColor;
vec2 encF(float v) {
    vec2 enc = fract(vec2(1.0, 255.0) * v);
    enc.x -= enc.y / 255.0;
    return enc;
}
float decF(vec2 v) { return dot(v, vec2(1.0, 1.0 / 255.0)); }
float sstep(float e0, float e1, float x) {
    float t = clamp((x - e0) / (e1 - e0), 0.0, 1.0);
    return t * t * (3.0 - 2.0 * t);
}
void main() {
    int steps = int(clamp(uRadius2 / max(uStride, 0.001), 1.0, 48.0));
    if (uPass == 0) {
        float nearOut = 16383.0;
        float nearIn = 16383.0;
        for (int i = -48; i < 48; i++) {
            if (i < -steps || i >= steps) continue;
            float x = float(i) * uStride;
            vec4 t = texture(uTex, vUv + vec2(x * uTexel.x, 0.0));
            nearOut = min(nearOut, t.a > 0.0 ? abs(x) : uRadius2 * 2.0);
            nearIn = min(nearIn, t.a < 0.003 ? abs(x) : uRadius2 * 2.0);
        }
        oColor = vec4(encF(nearOut / 16383.0), encF(nearIn / 16383.0));
        return;
    }
    float nearOut = 16383.0;
    float nearIn = 16383.0;
    for (int i = -48; i < 48; i++) {
        if (i < -steps || i >= steps) continue;
        float y = float(i) * uStride;
        vec4 b = texture(uBuf, vUv + vec2(0.0, y * uTexel.y));
        float xOut = decF(b.rg) * 16383.0;
        float xIn = decF(b.ba) * 16383.0;
        nearOut = min(nearOut, length(vec2(xOut, abs(y))));
        nearIn = min(nearIn, length(vec2(xIn, abs(y))));
    }
    vec4 tex = texture(uTex, vUv);
    float p = 0.5 + nearIn / uRadius2 - nearOut / uRadius2;
    if (p <= 0.0001) { oColor = tex; return; }   // AM discards here; the twin is continuous with it
    vec3 blend = uColor;
    int bm = int(uP[2] + 0.5);
    if (bm == 1 || bm == 2) {
        vec3 comp = texture(uComp, uRect.xy + vUv * uRect.zw).rgb;
        blend = bm == 1 ? comp * uColor : 1.0 - (1.0 - comp) * (1.0 - uColor);
    }
    float e0 = (1.0 - 1.5 / uRadius2) * uP[1];
    float oc = 1.0 - sstep(e0, 1.0, 1.0 - p);
    vec4 outC = vec4(blend * uP[0] * oc, uP[0] * oc * (bm == 0 ? uColorA : 1.0));
    oColor = tex + outC * (1.0 - tex.a);
}
)";

// Electric Edges (Alight's electricedges main pass; its blurBuf is produced by
// fxBlur at the def's fixed strength 0.42 through the gaussianblur calibration, and
// its `comp` input is sampled but never used in the def, so none is bound). All
// field maths runs in AM's layer space (y down): st is built flipped, the blur taps
// flip back. fxElectricEdgesAmPixel in fx_plan.cpp is the CPU twin.
const char* kElectricEdgesAm = R"(#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform sampler2D uBlur;
uniform vec2 uTexel;
uniform float uAspect;
uniform float uP[7];
uniform float uQ[7];
uniform float uR[7];
uniform vec3 uC1;
uniform vec3 uC2;
uniform vec3 uMaskCol;
in vec2 vUv;
out vec4 oColor;
vec3 amMod289(vec3 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
vec4 amMod289(vec4 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
vec4 amPermute(vec4 x) { return amMod289(((x * 34.0) + 1.0) * x); }
vec4 amTaylorInvSqrt(vec4 r) { return 1.79284291400159 - 0.85373472095314 * r; }
float amSnoise3(vec3 v) {
    const vec2 C = vec2(1.0 / 6.0, 1.0 / 3.0);
    const vec4 D = vec4(0.0, 0.5, 1.0, 2.0);
    vec3 i = floor(v + dot(v, C.yyy));
    vec3 x0 = v - i + dot(i, C.xxx);
    vec3 g = step(x0.yzx, x0.xyz);
    vec3 l = 1.0 - g;
    vec3 i1 = min(g.xyz, l.zxy);
    vec3 i2 = max(g.xyz, l.zxy);
    vec3 x1 = x0 - i1 + C.xxx;
    vec3 x2 = x0 - i2 + C.yyy;
    vec3 x3 = x0 - D.yyy;
    i = amMod289(i);
    vec4 p = amPermute(amPermute(amPermute(
        i.z + vec4(0.0, i1.z, i2.z, 1.0))
        + i.y + vec4(0.0, i1.y, i2.y, 1.0))
        + i.x + vec4(0.0, i1.x, i2.x, 1.0));
    float n_ = 0.142857142857;
    vec3 ns = n_ * D.wyz - D.xzx;
    vec4 j = p - 49.0 * floor(p * ns.z * ns.z);
    vec4 x_ = floor(j * ns.z);
    vec4 y_ = floor(j - 7.0 * x_);
    vec4 x = x_ * ns.x + ns.yyyy;
    vec4 y = y_ * ns.x + ns.yyyy;
    vec4 h = 1.0 - abs(x) - abs(y);
    vec4 b0 = vec4(x.xy, y.xy);
    vec4 b1 = vec4(x.zw, y.zw);
    vec4 s0 = floor(b0) * 2.0 + 1.0;
    vec4 s1 = floor(b1) * 2.0 + 1.0;
    vec4 sh = -step(h, vec4(0.0));
    vec4 a0 = b0.xzyw + s0.xzyw * sh.xxyy;
    vec4 a1 = b1.xzyw + s1.xzyw * sh.zzww;
    vec3 p0 = vec3(a0.xy, h.x);
    vec3 p1 = vec3(a0.zw, h.y);
    vec3 p2 = vec3(a1.xy, h.z);
    vec3 p3 = vec3(a1.zw, h.w);
    vec4 norm = amTaylorInvSqrt(vec4(dot(p0, p0), dot(p1, p1), dot(p2, p2), dot(p3, p3)));
    p0 *= norm.x;
    p1 *= norm.y;
    p2 *= norm.z;
    p3 *= norm.w;
    vec4 m = max(0.6 - vec4(dot(x0, x0), dot(x1, x1), dot(x2, x2), dot(x3, x3)), 0.0);
    m = m * m;
    return 42.0 * dot(m * m, vec4(dot(p0, x0), dot(p1, x1), dot(p2, x2), dot(p3, x3)));
}
float amTurbulence(vec2 st, int octaves, vec2 parallax, float evolution) {
    float value = 0.0;
    float amplitude = 0.5;
    for (int i = 0; i < 9; i++) {
        if (i >= octaves) break;
        value += amplitude * abs(amSnoise3(vec3(st, evolution)));
        st *= 2.0;
        st += parallax;
        amplitude *= 0.5;
    }
    return value;
}
vec2 random2(vec2 p) {
    return fract(sin(vec2(dot(p, vec2(127.1, 311.7)), dot(p, vec2(269.5, 183.3)))) * 43758.5453);
}
vec2 vnoise2(vec2 st) {
    vec2 i = floor(st);
    vec2 f = fract(st);
    vec2 a = random2(i);
    vec2 b = random2(i + vec2(1.0, 0.0));
    vec2 c = random2(i + vec2(0.0, 1.0));
    vec2 d = random2(i + vec2(1.0, 1.0));
    vec2 u = f;
    return mix(a, b, u.x) + (c - a) * u.y * (1.0 - u.x) + (d - b) * u.x * u.y;
}
mat2 rot2d(float a) {
    float c = cos(a), s = sin(a);
    return mat2(c, s, -s, c);
}
float sstep(float e0, float e1, float x) {
    float t = clamp((x - e0) / (e1 - e0), 0.0, 1.0);
    return t * t * (3.0 - 2.0 * t);
}
void main() {
    vec2 st = vec2(vUv.x - 0.5, 0.5 - vUv.y);
    st.x *= uAspect;
    vec2 evoseed = 3.0 * uQ[2] + vec2(62.5587, 137.1935) * uQ[1];
    vec2 l1 = vnoise2(evoseed + (st * rot2d(0.1)) * 2.0 * uP[2]);
    vec2 l2 = vnoise2(evoseed + (st * rot2d(0.3)) * 4.0 * uP[2]);
    vec2 l3 = vnoise2(evoseed + (st * rot2d(1.42)) * 8.0 * uP[2]);
    vec2 l4 = vnoise2(evoseed + (st * rot2d(2.534)) * 16.0 * uP[2]);
    vec2 offs = abs(abs(l1 - l2) - abs(l3 - l4));
    vec4 texColor = texture(uTex, vUv);
    float midA = texture(uBlur, vUv).a;
    float upA = texture(uBlur, vUv + vec2(0.0, 4.0 * uTexel.y)).a;
    float leftA = texture(uBlur, vUv + vec2(-4.0 * uTexel.x, 0.0)).a;
    vec2 dxy = vec2(midA - leftA, midA - upA);
    float dl = length(dxy);
    vec2 norm = dl > 0.000001 ? dxy / dl : vec2(0.0);
    vec2 doff = norm * length(offs) * (uP[3] * 0.055);
    vec4 blurred = texture(uBlur, vUv + vec2(doff.x, -doff.y));
    float turbBase = amTurbulence((st - 0.5) / uQ[6] + 0.5, 3, vec2(0.0), uQ[5] + 91.935 * uR[0]);
    float turb = sstep(uQ[4] - 0.22, uQ[4] - 0.12, turbBase);
    float suppress = sstep(uQ[4] - 0.12, uQ[4] - 0.02, turbBase);
    float ttint = sstep(uQ[4] - 0.02, uQ[4] - 0.001, turbBase);
    float p = blurred.a;
    float dist = 1.0 - (uP[0] * 0.4 + 0.5);
    float eb = 1.0 - uP[1] * 0.9 * suppress;
    float win = sstep(0.01, 0.12, p) * sstep(1.0, 0.999, p);
    float pp = pow(1.0 - abs(p - dist), eb * 30.0) * win;
    float qq = pow(1.0 - abs(p - dist), eb * 25.0) * win;
    if (uR[2] > 0.5) {
        float arad = uR[5] * 0.0174533;
        vec2 st2 = st;
        st2.y += midA * 0.1;
        st2 = st2 * rot2d(length(offs * uP[3] * 1.1) * 0.2 + arad);
        st2.y += midA * 0.1;
        float lines = clamp(sin((st2.y + uR[4] + offs.y * uP[3] * 0.015) * uR[3]), 0.0, 1.0)
                      * sstep(0.1, 0.2, midA);
        pp += pow(lines, eb * 30.0);
        qq += pow(lines, eb * 25.0);
    }
    vec4 c = vec4(pow(vec3(pp), 1.0 / max(uC1, vec3(0.000001))), pp) * uP[6]
           + vec4(pow(vec3(qq), 1.0 / max(uC2, vec3(0.000001))), qq) * uQ[0];
    c = clamp(c, 0.0, 1.0);
    c *= mix(vec4(uMaskCol, 1.0), vec4(1.0), ttint) * turb;
    oColor = texColor * uQ[3] * (1.0 - c.a) + c;
}
)";

// Smooth Edges (Alight's smoothedges): uPass 0 / 1 run the def's streak composites
// (x and y directional over-accumulations, composited over the input) — pass 1 reads
// HSTREAK for BOTH streaks, the def's own quirk (its y pass names hstreak where
// vstreak was meant); uPass 2 cuts the base (vstreak, or the input when maskToLayer)
// by the blurred alpha's smoothstep. The streak offsets are AM's (y down): the y
// taps flip into the engine's y-up targets, the accumulation side keeps AM's sign.
// uP: strength, mask to layer, dist (= strength * 0.05). fxSmoothEdgesAmPixel is
// the CPU twin.
const char* kSmoothEdgesAm = R"(#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform sampler2D uAux;
uniform sampler2D uBlur;
uniform int uPass;
uniform vec3 uP;
uniform vec2 uSize;
in vec2 vUv;
out vec4 oColor;
vec4 streak(bool fromAux, bool horizontal) {
    float dist = uP.z;
    int n = int(clamp(dist * (horizontal ? uSize.x : uSize.y), 1.01, 100.01));
    vec4 c = vec4(0.0);
    for (int i = 1; i < 100; i++) {
        if (i >= n) break;
        float off = dist * (float(i) / float(n - 1) - 0.5);
        vec2 duv = horizontal ? vec2(off, 0.0) : vec2(0.0, -off);
        vec4 tc = fromAux ? texture(uAux, vUv + duv) : texture(uTex, vUv + duv);
        if (off < 0.0) c = c * (1.0 - tc.a) + tc;
        else c = tc * (1.0 - c.a) + c;
    }
    return c;
}
vec4 over(vec4 a, vec4 b) { return a * (1.0 - b.a) + b; }
void main() {
    if (uPass == 2) {
        vec4 base = uP.y > 0.5 ? texture(uTex, vUv) : texture(uAux, vUv);
        float ba = texture(uBlur, vUv).a;
        float dd = 0.05 / ((1.0 + uP.x) * (1.0 + uP.x));
        float t = clamp((ba - (0.5 - dd)) / (2.0 * dd), 0.0, 1.0);
        oColor = base * (t * t * (3.0 - 2.0 * t));
        return;
    }
    bool fromAux = uPass == 1;
    vec4 cx = streak(fromAux, true);
    vec4 cy = streak(fromAux, false);
    oColor = over(over(cx, cy), texture(uTex, vUv));
}
)";

const char* kSources[EF_COUNT] = {kDown, kBlur, kCopy, kGlowUp, kGlowComp, kSplit, kGrain, kVig, kMask, kMaskApply, kChroma,
                                  fxsrc::kBlur2, fxsrc::kColor, fxsrc::kWarp, fxsrc::kStyle, kCopyBg, kGlowScan,
                                  kLumaKey, kFourGradient, kTurbulentAm, kPixelateAm, kChromaKeyAm, kSoftGlow, kFillBehind,
                                  kDisplaceMap, kSmoothBevel, kLightGlow, kInnerGlow, kDarkGlowExtract, kDarkGlowComp, kExtrude,
                                  kEdgeGlowEdge, kEdgeGlowComp,
                                  kChannelMapAm, kMosaicAm, kBoxPair, kGlowOuter, kElectricEdgesAm,
                                  kSmoothEdgesAm, kDeepGlowComp, kDropShadow,
                                  kBbMaker, kUnsharpComp, kNewSharpenComp, kAmBlendGen,
                                  kDeepGlow1Comp, kSpillStreak, kSpillOver, kSpillFinal};
static_assert(EF_COUNT <= 52, "eff_[] in renderer.h is too small");

uint32_t compile(GLenum type, const char* src) {
    const GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &src, nullptr);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[1024] = {0};
        glGetShaderInfoLog(s, sizeof(log) - 1, nullptr, log);
        LOGE("shader compile failed: %s", log);
        glDeleteShader(s);
        return 0;
    }
    return s;
}

uint32_t link(const char* fragment) {
    const uint32_t vs = compile(GL_VERTEX_SHADER, kVert);
    const uint32_t fs = compile(GL_FRAGMENT_SHADER, fragment);
    if (!vs || !fs) {
        if (vs) glDeleteShader(vs);
        if (fs) glDeleteShader(fs);
        return 0;
    }
    const uint32_t prog = glCreateProgram();
    glAttachShader(prog, vs);
    glAttachShader(prog, fs);
    glLinkProgram(prog);
    glDeleteShader(vs);
    glDeleteShader(fs);
    GLint ok = 0;
    glGetProgramiv(prog, GL_LINK_STATUS, &ok);
    if (!ok) {
        char log[1024] = {0};
        glGetProgramInfoLog(prog, sizeof(log) - 1, nullptr, log);
        LOGE("program link failed: %s", log);
        glDeleteProgram(prog);
        return 0;
    }
    return prog;
}

bool makeTarget(int w, int h, bool half, uint32_t* tex, uint32_t* fbo) {
    glGenTextures(1, tex);
    glBindTexture(GL_TEXTURE_2D, *tex);
    if (half) glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, w, h, 0, GL_RGBA, GL_HALF_FLOAT, nullptr);
    else glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glGenFramebuffers(1, fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, *fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, *tex, 0);
    return glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
}

void dropTarget(uint32_t* tex, uint32_t* fbo) {
    if (*fbo) glDeleteFramebuffers(1, fbo);
    if (*tex) glDeleteTextures(1, tex);
    *tex = *fbo = 0;
}

// One-channel (R8) target for the mask coverage.
bool makeTargetR8(int w, int h, uint32_t* tex, uint32_t* fbo) {
    glGenTextures(1, tex);
    glBindTexture(GL_TEXTURE_2D, *tex);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, w, h, 0, GL_RED, GL_UNSIGNED_BYTE, nullptr);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glGenFramebuffers(1, fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, *fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, *tex, 0);
    return glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
}

constexpr uint32_t kFailed = 0xFFFFFFFFu;

inline int halfSize(int v, int i) { return std::max(1, (v + (1 << (i + 1)) - 1) >> (i + 1)); }

void bindTex(int unit, uint32_t tex) {
    glActiveTexture(GL_TEXTURE0 + unit);
    glBindTexture(GL_TEXTURE_2D, tex);
}
inline GLint loc(uint32_t p, const char* n) { return glGetUniformLocation(p, n); }

// Starts a full-screen pass into `fbo` (picture size w x h).
void begin(uint32_t prog, uint32_t fbo, int w, int h) {
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glViewport(0, 0, w, h);
    glUseProgram(prog);
}
inline void draw() { glDrawArrays(GL_TRIANGLE_STRIP, 0, 4); }

}  // namespace

uint32_t Renderer::effProg(int id) {
    if (id < 0 || id >= EF_COUNT) return 0;
    if (eff_[id] == kFailed) return 0;  // failed once: that effect is skipped, do not retry every frame
    if (!eff_[id]) {
        eff_[id] = link(kSources[id]);
        if (!eff_[id]) {
            eff_[id] = kFailed;
            return 0;
        }
    }
    return eff_[id];
}

bool Renderer::ensureLevel(int i, int w, int h) {
    if (i < 0 || i >= kFxGlowLevels) return false;
    FxLevel& L = lv_[i];
    if (L.tex[0] && L.w == w && L.h == h) return true;
    for (int k = 0; k < 2; ++k) dropTarget(&L.tex[k], &L.fbo[k]);
    L.w = L.h = 0;
    if (lvHalf_ < 0) {
        const char* ext = (const char*)glGetString(GL_EXTENSIONS);
        lvHalf_ = (ext && (strstr(ext, "GL_EXT_color_buffer_float") || strstr(ext, "GL_EXT_color_buffer_half_float"))) ? 1 : 0;
    }
    for (int attempt = 0; attempt < 2; ++attempt) {
        const bool half = lvHalf_ == 1;
        bool ok = makeTarget(w, h, half, &L.tex[0], &L.fbo[0]) && makeTarget(w, h, half, &L.tex[1], &L.fbo[1]);
        if (ok) {
            L.w = w;
            L.h = h;
            return true;
        }
        for (int k = 0; k < 2; ++k) dropTarget(&L.tex[k], &L.fbo[k]);
        if (!half) break;
        lvHalf_ = 0;  // this phone cannot render to RGBA16F: use 8 bit
    }
    LOGE("effect level %d (%dx%d) incomplete", i, w, h);
    return false;
}

void Renderer::releaseEffects() {
    for (int i = 0; i < kFxGlowLevels; ++i) {
        for (int k = 0; k < 2; ++k) dropTarget(&lv_[i].tex[k], &lv_[i].fbo[k]);
        lv_[i].w = lv_[i].h = 0;
    }
    for (auto& p : eff_) {
        if (p && p != kFailed) glDeleteProgram(p);
        p = 0;
    }
    lvHalf_ = -1;
    for (int i = 0; i < 2; ++i) dropTarget(&maskTex_[i], &maskFbo_[i]);
    maskW_ = maskH_ = 0;
}

void Renderer::runEffects(const LayerFx& fx, int W, int H, int* cur) {
    for (int k = 0; k < fx.nOps; ++k) {
        const FxOp& op = fx.ops[k];
        bool ok = true;
        switch (op.type) {
            case FX_BLUR: ok = fxBlur(op, W, H, cur); break;
            case FX_GLOW: ok = fxGlow(op, W, H, cur); break;
            case FX_RGB_SPLIT: ok = fxSplit(op, W, H, cur); break;
            case FX_GRAIN: ok = fxGrain(op, W, H, cur, fx.grainFrame); break;
            case FX_VIGNETTE: ok = fxVignette(op, W, H, cur); break;
            case FX_CHROMA: ok = fxChroma(op, W, H, cur); break;
            case FX_COPY_BG: ok = fxCopyBg(op, W, H, cur); break;
            case FX_GLOW_SCAN: ok = fxGlowScan(op, W, H, cur); break;
            case FX_LUMA_KEY: ok = fxLumaKey(op, W, H, cur); break;
            case FX_FOUR_COLOR_GRADIENT: ok = fxFourColorGradient(op, W, H, cur); break;
            case FX_TURBULENT_DISPLACE_AM: ok = fxTurbulentAm(op, W, H, cur); break;
            case FX_PIXELATE_AM: ok = fxPixelateAm(op, W, H, cur); break;
            case FX_CHROMA_KEY_AM: ok = fxChromaKeyAm(op, W, H, cur); break;
            case FX_SOFT_GLOW_AM: ok = fxSoftGlowAm(op, W, H, cur); break;
            case FX_FILL_BEHIND_AM: ok = fxFillBehindAm(op, W, H, cur); break;
            case FX_DARK_GLOW_AM: ok = fxDarkGlowAm(op, W, H, cur); break;
            case FX_EDGE_GLOW_AM: ok = fxEdgeGlowAm(op, W, H, cur); break;
            case FX_DISPLACE_MAP_AM:
            case FX_DISPLACE_MAP_RAD_AM: ok = fxDisplaceMapAm(op, W, H, cur); break;
            case FX_SMOOTH_BEVEL_AM: ok = fxSmoothBevelAm(op, W, H, cur); break;
            case FX_LIGHT_GLOW_AM: ok = fxLightGlowAm(op, W, H, cur); break;
            case FX_INNER_GLOW_AM: ok = fxInnerGlowAm(op, W, H, cur); break;
            case FX_EXTRUDE_AM:
            case FX_EXTRUDE_FLAT_AM: ok = fxExtrudeAm(op, W, H, cur); break;
            case FX_CHANNELMAP_AM: ok = fxChannelMapAm(op, W, H, cur); break;
            case FX_MOSAIC_AM: ok = fxMosaicAm(op, W, H, cur); break;
            case FX_BOX_BLUR3_AM: ok = fxBoxBlur3Am(op, W, H, cur); break;
            case FX_INNER_BLUR_AM: ok = fxInnerBlurAm(op, W, H, cur); break;
            case FX_FEATHER_AM: ok = fxFeatherAm(op, W, H, cur); break;
            case FX_GLOW_AM: ok = fxGlowOuterAm(op, W, H, cur); break;
            case FX_ELECTRIC_EDGES_AM: ok = fxElectricEdgesAm(op, W, H, cur); break;
            case FX_SMOOTH_EDGES_AM: ok = fxSmoothEdgesAm(op, W, H, cur); break;
            case FX_GAUSSIAN_BLUR_AM: ok = fxGaussianBlurAm(op, W, H, cur); break;
            case FX_DEEP_GLOW2_AM: ok = fxDeepGlow2Am(op, W, H, cur); break;
            case FX_DROP_SHADOW_AM: ok = fxDropShadowAm(op, W, H, cur); break;
            case FX_BBMAKER_AM: ok = fxBbMakerAm(op, W, H, cur); break;
            case FX_UNSHARP_MASK_AM: ok = fxUnsharpMaskAm(op, W, H, cur); break;
            case FX_NEWSHARPEN_AM: ok = fxNewSharpenAm(op, W, H, cur); break;
            case FX_GRADSHADOW_AM:
            case FX_GRADIENTFLOW_AM:
            case FX_PARTX_AM:
            case FX_EDGELINE_AM:
            case FX_LIGHTSWEEP_AM:
            case FX_COLORGRAD3_AM: ok = fxAmBlendGenAm(op, W, H, cur); break;
            case FX_DEEP_GLOW_AM: ok = fxDeepGlow1Am(op, W, H, cur); break;
            case FX_SPILL_CLEANUP_AM: ok = fxSpillCleanupAm(op, W, H, cur); break;
            default:
                // The shaded effects are the ones fxShaderOf maps to a shared program (7..53 and, since
                // v160, the imported Alight effects at 59..62); anything else here is not for the engine.
                if (fxShaderOf(op.type).group != FXG_NONE) ok = fxShaded(op, W, H, cur, fx.grainFrame);
                break;
        }
        if (!ok) LOGE("effect %d skipped (shader or target failed)", op.type);
    }
}

bool Renderer::ensureMaskTargets(int w, int h) {
    if (maskTex_[0] && maskW_ == w && maskH_ == h) return true;
    for (int i = 0; i < 2; ++i) dropTarget(&maskTex_[i], &maskFbo_[i]);
    maskW_ = maskH_ = 0;
    bool ok = makeTargetR8(w, h, &maskTex_[0], &maskFbo_[0]) && makeTargetR8(w, h, &maskTex_[1], &maskFbo_[1]);
    if (!ok) {
        LOGE("mask targets (%dx%d) incomplete", w, h);
        for (int i = 0; i < 2; ++i) dropTarget(&maskTex_[i], &maskFbo_[i]);
        return false;
    }
    maskW_ = w;
    maskH_ = h;
    return true;
}

// Masks of the layer: the coverage picture is built mask by mask (ping-pong between two R8 targets), then the layer
// picture is multiplied by it. Returns false (picture unchanged) when a shader or target is not available.
bool Renderer::runMasks(const LayerFx& fx, int W, int H, int* cur) {
    if (fx.nMasks <= 0) return true;
    const uint32_t prog = effProg(EF_MASK), apply = effProg(EF_MASK_APPLY);
    if (!prog || !apply || !ensureMaskTargets(W, H)) return false;
    glBindFramebuffer(GL_FRAMEBUFFER, maskFbo_[0]);
    glViewport(0, 0, W, H);
    glClearColor(maskInitial(fx.masks, fx.nMasks), 0.f, 0.f, 0.f);
    glClear(GL_COLOR_BUFFER_BIT);
    int a = 0;  // target that holds the coverage so far
    for (int k = 0; k < fx.nMasks; ++k) {
        const MaskOp& m = fx.masks[k];
        const int dst = 1 - a;
        begin(prog, maskFbo_[dst], W, H);
        bindTex(0, maskTex_[a]);
        glUniform1i(loc(prog, "uPrev"), 0);
        glUniform2f(loc(prog, "uSize"), (float)W, (float)H);
        glUniform1i(loc(prog, "uShape"), m.shape);
        glUniform1i(loc(prog, "uMode"), m.mode);
        glUniform1f(loc(prog, "uInvert"), m.invert ? 1.f : 0.f);
        glUniform2f(loc(prog, "uC"), m.cx, m.cy);
        glUniform2f(loc(prog, "uHalf"), m.hw, m.hh);
        glUniform2f(loc(prog, "uRot"), m.cosR, m.sinR);
        glUniform1f(loc(prog, "uFeather"), m.feather);
        glUniform1f(loc(prog, "uExpand"), m.expand);
        glUniform1f(loc(prog, "uOpacity"), m.opacity);
        glUniform1f(loc(prog, "uCorner"), m.corner);
        glUniform1f(loc(prog, "uAn"), m.anRad);
        glUniform2f(loc(prog, "uAnCs"), m.anCos, m.anSin);
        glUniform2f(loc(prog, "uEn"), m.enCos, m.enSin);
        glUniform1i(loc(prog, "uN"), m.nPts);
        if (m.shape == MS_PATH && m.nPts > 0) glUniform2fv(loc(prog, "uPts"), m.nPts, m.pts);
        draw();
        a = dst;
    }
    const int dst = 1 - *cur;
    begin(apply, fxFbo_[dst], W, H);
    bindTex(0, fxTex_[*cur]);
    bindTex(1, maskTex_[a]);
    glUniform1i(loc(apply, "uTex"), 0);
    glUniform1i(loc(apply, "uMask"), 1);
    draw();
    *cur = dst;
    return true;
}

bool Renderer::fxBlur(const FxOp& op, int W, int H, int* cur) {
    return fxBlurPx(fxBlurSigma(op.p[0], (float)H / 1080.f), W, H, cur);
}

// The separable gaussian at an explicit sigma in px (the fxBlur body; v176 split it
// out so the Alight passes that wire the host gaussianblur def can drive it with the
// def's own sigma — fxAmGaussianSigma — instead of round-tripping through the app
// blur's value, whose 0..100 clamp silently capped sigma at 60 x H / 1080).
bool Renderer::fxBlurPx(float sigma, int W, int H, int* cur) {
    if (sigma < 0.25f) return true;
    const uint32_t down = effProg(EF_DOWN), blur = effProg(EF_BLUR), copy = effProg(EF_COPY);
    if (!blur || (!down && fxPlanBlur(sigma).shift > 0) || !copy) return false;
    const BlurPlan bp = fxPlanBlur(sigma);
    const GaussKernel k = fxGaussKernel(bp.sigmaLow);

    auto blurPass = [&](uint32_t srcTex, uint32_t dstFbo, int w, int h, float dx, float dy) {
        begin(blur, dstFbo, w, h);
        bindTex(0, srcTex);
        glUniform1i(loc(blur, "uTex"), 0);
        glUniform2f(loc(blur, "uDir"), dx, dy);
        glUniform1f(loc(blur, "uCentre"), k.centre);
        glUniform1i(loc(blur, "uN"), k.n);
        glUniform1fv(loc(blur, "uOff"), kFxMaxTaps, k.offset);
        glUniform1fv(loc(blur, "uWt"), kFxMaxTaps, k.weight);
        draw();
    };

    if (bp.shift == 0) {
        const int a = *cur, b = 1 - *cur;
        blurPass(fxTex_[a], fxFbo_[b], W, H, 1.f / W, 0.f);
        blurPass(fxTex_[b], fxFbo_[a], W, H, 0.f, 1.f / H);
        return true;  // the result is back in `cur`
    }
    // Big radius: reduce the picture (13-tap, no aliasing), blur the small one, scale it back up (bilinear).
    uint32_t srcTex = fxTex_[*cur];
    int sw = W, sh = H;
    for (int i = 0; i < bp.shift; ++i) {
        const int lw = halfSize(W, i), lh = halfSize(H, i);
        if (!ensureLevel(i, lw, lh)) return false;
        begin(down, lv_[i].fbo[0], lw, lh);
        bindTex(0, srcTex);
        glUniform1i(loc(down, "uTex"), 0);
        glUniform2f(loc(down, "uTexel"), 1.f / sw, 1.f / sh);
        glUniform1i(loc(down, "uPre"), 0);
        glUniform2f(loc(down, "uPreP"), 0.f, 0.f);
        draw();
        srcTex = lv_[i].tex[0];
        sw = lw;
        sh = lh;
    }
    const FxLevel& L = lv_[bp.shift - 1];
    blurPass(L.tex[0], L.fbo[1], L.w, L.h, 1.f / L.w, 0.f);
    blurPass(L.tex[1], L.fbo[0], L.w, L.h, 0.f, 1.f / L.h);
    const int dst = 1 - *cur;
    begin(copy, fxFbo_[dst], W, H);
    bindTex(0, L.tex[0]);
    glUniform1i(loc(copy, "uTex"), 0);
    draw();
    *cur = dst;
    return true;
}

bool Renderer::fxGlow(const FxOp& op, int W, int H, int* cur) {
    const GlowPlan gp = fxPlanGlow(op);
    if (gp.intensity <= 0.f) return true;
    const uint32_t down = effProg(EF_DOWN), up = effProg(EF_GLOW_UP), comp = effProg(EF_GLOW_COMP);
    if (!down || !up || !comp) return false;

    // how many levels fit: the smallest one must stay >= 2 px
    int n = 0;
    while (n < kFxGlowLevels && std::min(halfSize(W, n), halfSize(H, n)) >= 2) ++n;
    if (n < 1) return true;
    for (int i = 0; i < n; ++i) {
        if (!ensureLevel(i, halfSize(W, i), halfSize(H, i))) return false;
    }
    // 1) bright pass into level 0, then the down chain
    uint32_t srcTex = fxTex_[*cur];
    int sw = W, sh = H;
    for (int i = 0; i < n; ++i) {
        begin(down, lv_[i].fbo[0], lv_[i].w, lv_[i].h);
        bindTex(0, srcTex);
        glUniform1i(loc(down, "uTex"), 0);
        glUniform2f(loc(down, "uTexel"), 1.f / sw, 1.f / sh);
        glUniform1i(loc(down, "uPre"), i == 0 ? 1 : 0);
        glUniform2f(loc(down, "uPreP"), gp.threshold, gp.knee);
        draw();
        srcTex = lv_[i].tex[0];
        sw = lv_[i].w;
        sh = lv_[i].h;
    }
    // 2) up chain: level i (tex 1) = mix(down picture of level i, tent-filtered level i + 1, spread)
    uint32_t coarse = lv_[n - 1].tex[0];
    int cw = lv_[n - 1].w, ch = lv_[n - 1].h;
    for (int i = n - 2; i >= 0; --i) {
        begin(up, lv_[i].fbo[1], lv_[i].w, lv_[i].h);
        bindTex(0, lv_[i].tex[0]);
        bindTex(1, coarse);
        glUniform1i(loc(up, "uFine"), 0);
        glUniform1i(loc(up, "uCoarse"), 1);
        glUniform2f(loc(up, "uCoarseTexel"), 1.f / cw, 1.f / ch);
        glUniform1f(loc(up, "uSpread"), gp.spread);
        draw();
        coarse = lv_[i].tex[1];
        cw = lv_[i].w;
        ch = lv_[i].h;
    }
    // 3) glow + picture
    const int dst = 1 - *cur;
    begin(comp, fxFbo_[dst], W, H);
    bindTex(0, fxTex_[*cur]);
    bindTex(1, coarse);
    glUniform1i(loc(comp, "uTex"), 0);
    glUniform1i(loc(comp, "uGlow"), 1);
    glUniform1f(loc(comp, "uGain"), gp.intensity * 2.f);
    draw();
    *cur = dst;
    return true;
}

// Glow Scan (Alight): the layer's own blurred copy is made with the standalone blur machinery, then
// the scan shader mixes it with the preserved original by the luma band. The original is preserved
// in the TOP pyramid level: fxBlur's pyramid uses levels 0..4 at most (its shift caps at 5), and a
// Glow running earlier in the same chain has finished with the levels by the time this runs.
bool Renderer::fxGlowScan(const FxOp& op, int W, int H, int* cur) {
    if (fxIsIdentity(op)) return true;
    const uint32_t prog = effProg(EF_GLOW_SCAN), copy = effProg(EF_COPY);
    if (!prog || !copy) return false;
    if (!ensureLevel(kFxGlowLevels - 1, W, H)) return false;
    const FxLevel& keep = lv_[kFxGlowLevels - 1];
    begin(copy, keep.fbo[0], W, H);
    bindTex(0, fxTex_[*cur]);
    glUniform1i(loc(copy, "uTex"), 0);
    draw();
    if (!fxBlurPx(fxGlowScanBlurSigma(op.p[3], std::hypot((float)width(), (float)height())), W, H, cur))
        return false;   // the blurred copy, back in `cur` (a no-op under sigma 0.25)
    const int dst = 1 - *cur;
    begin(prog, fxFbo_[dst], W, H);
    bindTex(0, keep.tex[0]);
    bindTex(1, fxTex_[*cur]);
    glUniform1i(loc(prog, "uTex"), 0);
    glUniform1i(loc(prog, "uBlur"), 1);
    auto cl01 = [](float v) { return std::min(100.f, std::max(0.f, v)) / 100.f; };
    glUniform4f(loc(prog, "uBand"), cl01(op.p[0]), cl01(op.p[1]),
                std::min(4.f, std::max(0.f, op.p[2])), 0.f);   // blend: no native slot yet (see kGlowScan)
    const int packed = (int)std::lround(op.p[6]);
    glUniform3f(loc(prog, "uFillColor"), ((packed >> 16) & 0xFF) / 255.f, ((packed >> 8) & 0xFF) / 255.f,
                (packed & 0xFF) / 255.f);
    glUniform2f(loc(prog, "uMix"), cl01(op.p[4]), cl01(op.p[5]));
    draw();
    *cur = dst;
    return true;
}

// Luma Key (Alight): a single pass scaling the layer's alpha by the luma band.
bool Renderer::fxLumaKey(const FxOp& op, int W, int H, int* cur) {
    if (fxIsIdentity(op)) return true;
    const uint32_t prog = effProg(EF_LUMA_KEY);
    if (!prog) return false;
    auto cl01 = [](float v) { return std::min(100.f, std::max(0.f, v)) / 100.f; };
    const int dst = 1 - *cur;
    begin(prog, fxFbo_[dst], W, H);
    bindTex(0, fxTex_[*cur]);
    glUniform1i(loc(prog, "uTex"), 0);
    glUniform4f(loc(prog, "uBand"), cl01(op.p[0]), cl01(op.p[1]), cl01(op.p[2]), op.p[3] > 0.5f ? 1.f : 0.f);
    glUniform1f(loc(prog, "uWeighted"), op.p[4] > 0.5f ? 1.f : 0.f);
    draw();
    *cur = dst;
    return true;
}

// Four Color Gradient (Alight): the packed colours / offsets of the op are unpacked into the
// shader's arrays here (the Glow Scan packed-colour precedent).
bool Renderer::fxFourColorGradient(const FxOp& op, int W, int H, int* cur) {
    if (fxIsIdentity(op)) return true;
    const uint32_t prog = effProg(EF_FOUR_GRADIENT);
    if (!prog) return false;
    float cols[12], offs[8];
    for (int i = 0; i < 4; ++i) {
        const int pk = (int)std::lround(std::min(16777215.f, std::max(0.f, op.p[i])));
        cols[3 * i] = ((pk >> 16) & 0xFF) / 255.f;
        cols[3 * i + 1] = ((pk >> 8) & 0xFF) / 255.f;
        cols[3 * i + 2] = (pk & 0xFF) / 255.f;
        const int pq = (int)std::lround(std::min(16777215.f, std::max(0.f, op.q[i])));
        offs[2 * i] = (float)(pq / 4096 - 2048);
        offs[2 * i + 1] = (float)(pq % 4096 - 2048);
    }
    const int dst = 1 - *cur;
    begin(prog, fxFbo_[dst], W, H);
    bindTex(0, fxTex_[*cur]);
    glUniform1i(loc(prog, "uTex"), 0);
    glUniform3fv(loc(prog, "uCol"), 4, cols);
    glUniform2fv(loc(prog, "uOff"), 4, offs);
    glUniform3f(loc(prog, "uMisc"), std::min(200.f, std::max(25.f, op.p[4])) / 100.f,
                std::min(100.f, std::max(0.f, op.p[5])) / 100.f, std::min(2.f, std::max(0.f, op.p[6])));
    draw();
    *cur = dst;
    return true;
}

// Turbulent Displace (Alight): a single pass; the whole op (p + q) rides as uniform arrays.
bool Renderer::fxTurbulentAm(const FxOp& op, int W, int H, int* cur) {
    if (fxIsIdentity(op)) return true;
    const uint32_t prog = effProg(EF_TURBULENT_AM);
    if (!prog) return false;
    const int dst = 1 - *cur;
    begin(prog, fxFbo_[dst], W, H);
    bindTex(0, fxTex_[*cur]);
    glUniform1i(loc(prog, "uTex"), 0);
    glUniform1f(loc(prog, "uAspect"), (float)W / (float)H);
    glUniform1fv(loc(prog, "uP"), 7, op.p);
    glUniform1fv(loc(prog, "uQ"), 7, op.q);
    draw();
    *cur = dst;
    return true;
}

// Pixelate (Alight): a single pass like Turbulent's, plus the picture size in pixels.
bool Renderer::fxPixelateAm(const FxOp& op, int W, int H, int* cur) {
    if (fxIsIdentity(op)) return true;
    const uint32_t prog = effProg(EF_PIXELATE_AM);
    if (!prog) return false;
    const int dst = 1 - *cur;
    begin(prog, fxFbo_[dst], W, H);
    bindTex(0, fxTex_[*cur]);
    glUniform1i(loc(prog, "uTex"), 0);
    glUniform2f(loc(prog, "uSize"), (float)W, (float)H);
    glUniform1fv(loc(prog, "uP"), 7, op.p);
    glUniform1fv(loc(prog, "uQ"), 7, op.q);
    draw();
    *cur = dst;
    return true;
}

// Chroma Key (Alight): a single pass scaling the layer's alpha by the YUV distance to the key
// colour, with AM's defringe and border rules.
bool Renderer::fxChromaKeyAm(const FxOp& op, int W, int H, int* cur) {
    if (fxIsIdentity(op)) return true;
    const uint32_t prog = effProg(EF_CHROMA_KEY_AM);
    if (!prog) return false;
    const int packed = (int)std::lround(std::min(16777215.f, std::max(0.f, op.p[0])));
    auto cl01 = [](float v) { return std::min(100.f, std::max(0.f, v)) / 100.f; };
    const int dst = 1 - *cur;
    begin(prog, fxFbo_[dst], W, H);
    bindTex(0, fxTex_[*cur]);
    glUniform1i(loc(prog, "uTex"), 0);
    glUniform3f(loc(prog, "uKey"), ((packed >> 16) & 0xFF) / 255.f, ((packed >> 8) & 0xFF) / 255.f,
                (packed & 0xFF) / 255.f);
    glUniform4f(loc(prog, "uParams"), cl01(op.p[1]), cl01(op.p[2]), op.p[3] > 0.5f ? 1.f : 0.f,
                op.p[4] > 0.5f ? 1.f : 0.f);
    draw();
    *cur = dst;
    return true;
}

// Soft Glow (Alight): the Glow Scan two-texture shape — keep the layer, blur a copy, composite.
bool Renderer::fxSoftGlowAm(const FxOp& op, int W, int H, int* cur) {
    if (fxIsIdentity(op)) return true;
    const uint32_t prog = effProg(EF_SOFT_GLOW), copy = effProg(EF_COPY);
    if (!prog || !copy) return false;
    if (!ensureLevel(kFxGlowLevels - 1, W, H)) return false;
    const FxLevel& keep = lv_[kFxGlowLevels - 1];
    begin(copy, keep.fbo[0], W, H);
    bindTex(0, fxTex_[*cur]);
    glUniform1i(loc(copy, "uTex"), 0);
    draw();
    if (!fxBlurPx(fxGlowScanBlurSigma(op.p[0], std::hypot((float)width(), (float)height())), W, H, cur))
        return false;   // AM strength -> the host gaussianblur sigma (the Glow Scan calibration)
    auto cl = [](float v) { return std::min(100.f, std::max(0.f, v)) / 100.f; };
    const int packed = (int)std::lround(std::min(16777215.f, std::max(0.f, op.p[4])));
    const int dst = 1 - *cur;
    begin(prog, fxFbo_[dst], W, H);
    bindTex(0, keep.tex[0]);
    bindTex(1, fxTex_[*cur]);
    glUniform1i(loc(prog, "uTex"), 0);
    glUniform1i(loc(prog, "uBlur"), 1);
    glUniform4f(loc(prog, "uAdj"), cl(op.p[1]), cl(op.p[2]), cl(op.p[3]), cl(op.p[5]));
    glUniform3f(loc(prog, "uColor"), ((packed >> 16) & 0xFF) / 255.f, ((packed >> 8) & 0xFF) / 255.f,
                (packed & 0xFF) / 255.f);
    glUniform1f(loc(prog, "uAlpha"), cl(op.p[6]));
    draw();
    *cur = dst;
    return true;
}

// Fill Behind (Alight): the Soft Glow shape — keep the layer, blur a copy, composite the
// adjusted blurred copy behind the feathered foreground.
bool Renderer::fxFillBehindAm(const FxOp& op, int W, int H, int* cur) {
    if (fxIsIdentity(op)) return true;
    const uint32_t prog = effProg(EF_FILL_BEHIND), copy = effProg(EF_COPY);
    if (!prog || !copy) return false;
    if (!ensureLevel(kFxGlowLevels - 1, W, H)) return false;
    const FxLevel& keep = lv_[kFxGlowLevels - 1];
    begin(copy, keep.fbo[0], W, H);
    bindTex(0, fxTex_[*cur]);
    glUniform1i(loc(copy, "uTex"), 0);
    draw();
    if (!fxBlurPx(fxGlowScanBlurSigma(op.p[0], std::hypot((float)width(), (float)height())), W, H, cur))
        return false;
    auto cl = [](float v) { return std::min(100.f, std::max(0.f, v)) / 100.f; };
    auto unpack = [](float packedF, float* r, float* g, float* b) {
        const int pk = (int)std::lround(std::min(16777215.f, std::max(0.f, packedF)));
        *r = ((pk >> 16) & 0xFF) / 255.f;
        *g = ((pk >> 8) & 0xFF) / 255.f;
        *b = (pk & 0xFF) / 255.f;
    };
    float tr, tg, tb, er, eg, eb;
    unpack(op.p[4], &tr, &tg, &tb);
    unpack(op.q[2], &er, &eg, &eb);
    const int dst = 1 - *cur;
    begin(prog, fxFbo_[dst], W, H);
    bindTex(0, keep.tex[0]);
    bindTex(1, fxTex_[*cur]);
    glUniform1i(loc(prog, "uTex"), 0);
    glUniform1i(loc(prog, "uBlur"), 1);
    glUniform2f(loc(prog, "uBgOff"), op.q[3], op.q[4]);
    glUniform3f(loc(prog, "uTint"), tr, tg, tb);
    glUniform4f(loc(prog, "uBgP"), cl(op.p[3]), cl(op.q[6]), std::max(0.01f, op.p[2] / 100.f),
                std::max(0.01f, cl(op.q[1]) <= 0.f ? 1.f : op.q[1] / 100.f));
    glUniform4f(loc(prog, "uFgP"), cl(op.p[6]), std::max(0.f, op.q[0]), cl(op.p[5]), cl(op.q[5]));
    glUniform3f(loc(prog, "uEdge"), er, eg, eb);
    glUniform2f(loc(prog, "uFeaP"), cl(op.p[1]), cl(op.p[0]));
    draw();
    *cur = dst;
    return true;
}

// Dark Glow (Alight): keep the layer, extract its dark parts, blur the extract with the def's
// kernel (its size runs off the scene diagonal — acPreviewSize — applied directly as a sigma
// through fxBlurPx since v176; before, it round-tripped through the app blur's value), then
// the multiply composite. At strength 0 the
// def composites the UNBLURRED extract, which the skipped blur (sigma < 0.25 is a no-op in
// fxBlurPx) reproduces exactly.
bool Renderer::fxDarkGlowAm(const FxOp& op, int W, int H, int* cur) {
    if (fxIsIdentity(op)) return true;
    const uint32_t ext = effProg(EF_DARK_GLOW_EXTRACT), comp = effProg(EF_DARK_GLOW_COMP), copy = effProg(EF_COPY);
    if (!ext || !comp || !copy) return false;
    if (!ensureLevel(kFxGlowLevels - 1, W, H)) return false;
    const FxLevel& keep = lv_[kFxGlowLevels - 1];
    begin(copy, keep.fbo[0], W, H);
    bindTex(0, fxTex_[*cur]);
    glUniform1i(loc(copy, "uTex"), 0);
    draw();
    auto cl = [](float v) { return std::min(100.f, std::max(0.f, v)) / 100.f; };
    const int dst0 = 1 - *cur;
    begin(ext, fxFbo_[dst0], W, H);
    bindTex(0, fxTex_[*cur]);
    glUniform1i(loc(ext, "uTex"), 0);
    const int packed = (int)std::lround(std::min(16777215.f, std::max(0.f, op.p[2])));
    glUniform3f(loc(ext, "uColor"), ((packed >> 16) & 0xFF) / 255.f, ((packed >> 8) & 0xFF) / 255.f,
                (packed & 0xFF) / 255.f);
    glUniform2f(loc(ext, "uP"), cl(op.p[1]), cl(op.p[3]));
    draw();
    *cur = dst0;
    const float diag = std::hypot((float)width(), (float)height());
    const float sigma = fxDarkGlowSigma(std::min(200.f, std::max(0.f, op.p[0])) / 100.f, diag);
    if (!fxBlurPx(sigma, W, H, cur)) return false;
    const int dst = 1 - *cur;
    begin(comp, fxFbo_[dst], W, H);
    bindTex(0, keep.tex[0]);
    bindTex(1, fxTex_[*cur]);
    glUniform1i(loc(comp, "uTex"), 0);
    glUniform1i(loc(comp, "uBlur"), 1);
    glUniform1f(loc(comp, "uAlpha"), cl(op.p[4]));
    draw();
    *cur = dst;
    return true;
}

// Edge Glow (Alight): keep the layer, run the def's findedges-edgeglow pass into the
// other fx target, blur that edge buffer with the host gaussianblur at the def's
// strength (fxEdgeGlowAmBlurSigma — strength 0 leaves it unblurred, exactly as AM's
// chain composites it then), then the def's composite over the kept layer.
bool Renderer::fxEdgeGlowAm(const FxOp& op, int W, int H, int* cur) {
    const uint32_t edge = effProg(EF_EDGE_GLOW_EDGE), comp = effProg(EF_EDGE_GLOW_COMP), copy = effProg(EF_COPY);
    if (!edge || !comp || !copy) return false;
    if (!ensureLevel(kFxGlowLevels - 1, W, H)) return false;
    const FxLevel& keep = lv_[kFxGlowLevels - 1];
    begin(copy, keep.fbo[0], W, H);
    bindTex(0, fxTex_[*cur]);
    glUniform1i(loc(copy, "uTex"), 0);
    draw();
    const int dst0 = 1 - *cur;
    begin(edge, fxFbo_[dst0], W, H);
    bindTex(0, fxTex_[*cur]);
    glUniform1i(loc(edge, "uTex"), 0);
    glUniform2f(loc(edge, "uTexel"), 1.f / std::max(1, W), 1.f / std::max(1, H));
    glUniform3f(loc(edge, "uP"), op.p[0], op.p[1], op.p[2]);   // smoothing, threshold, invert
    draw();
    *cur = dst0;
    if (!fxBlurPx(fxEdgeGlowAmBlurSigma(op.p[3], std::hypot((float)width(), (float)height())), W, H, cur))
        return false;   // strength, the def's "Blur"
    auto unpack = [](float packedF, float* r, float* g, float* b) {
        const int pk = (int)std::lround(std::min(16777215.f, std::max(0.f, packedF)));
        *r = ((pk >> 16) & 0xFF) / 255.f;
        *g = ((pk >> 8) & 0xFF) / 255.f;
        *b = (pk & 0xFF) / 255.f;
    };
    float fr, fg, fb, tr, tg, tb;
    unpack(op.p[5], &fr, &fg, &fb);
    unpack(op.q[2], &tr, &tg, &tb);
    const int dst = 1 - *cur;
    begin(comp, fxFbo_[dst], W, H);
    bindTex(0, keep.tex[0]);
    bindTex(1, fxTex_[*cur]);
    glUniform1i(loc(comp, "uTex"), 0);
    glUniform1i(loc(comp, "uBlur"), 1);
    glUniform3f(loc(comp, "uFill"), fr, fg, fb);
    glUniform3f(loc(comp, "uTint"), tr, tg, tb);
    glUniform4f(loc(comp, "uP"), op.p[4], op.q[0], op.q[1], op.q[3]);   // spread, gamma, brightness, tint
    glUniform1f(loc(comp, "uBlend"), op.p[6]);
    draw();
    *cur = dst;
    return true;
}

// The standalone gaussianblur (Alight): the def IS the host blur — sigma from the
// def's kernel at the frame diagonal (fxAmGaussianSigma), applied at full res.
// AM's previewSize is the renderer's current frame (width() x height()) — the size
// the layer is being composited into, the fxDarkGlowAm precedent; the fx pass runs
// on the layer's crop buffer (W x H) but the kernel scales with the frame, exactly
// as AM's scales with its preview.
bool Renderer::fxGaussianBlurAm(const FxOp& op, int W, int H, int* cur) {
    if (fxIsIdentity(op)) return true;
    return fxBlurPx(fxAmGaussianSigma(op.p[0], std::hypot((float)width(), (float)height())), W, H, cur);
}

// Deep Glow 2 (Alight; deepglow2 and deepglow_enc share the def): per iteration —
// keep the layer, blur a copy with the host gaussianblur at `strength`, composite
// (EF_DEEP_GLOW_COMP, the def's shader). The packed points unpack through
// ClipFx.packPoint's 12-bit fields, whole AM units.
bool Renderer::fxDeepGlow2Am(const FxOp& op, int W, int H, int* cur) {
    if (fxIsIdentity(op)) return true;
    const uint32_t prog = effProg(EF_DEEP_GLOW_COMP), copy = effProg(EF_COPY);
    if (!prog || !copy) return false;
    if (!ensureLevel(kFxGlowLevels - 1, W, H)) return false;
    const FxLevel& keep = lv_[kFxGlowLevels - 1];
    auto unpackPt = [](float packedF, float* x, float* y) {
        const int pk = (int)std::lround(packedF);
        *x = (float)(pk / 4096 - 2048);
        *y = (float)(pk % 4096 - 2048);
    };
    float posX, posY, strX, strY, oftX, oftY;
    unpackPt(op.r[0], &posX, &posY);
    unpackPt(op.r[1], &strX, &strY);
    unpackPt(op.r[2], &oftX, &oftY);
    const int flags = (int)std::lround(op.r[3]);
    const int iter = std::min(3, std::max(1, (int)std::lround(op.r[4])));
    const int packedCol = (int)std::lround(std::min(16777215.f, std::max(0.f, op.q[4])));
    const float diag = std::hypot((float)width(), (float)height());
    const float sigma = fxAmGaussianSigma(op.p[0], diag);
    const float rad = op.q[6] * 0.0174533f;   // angleee, raw degrees (the def's radians())
    for (int it = 0; it < iter; ++it) {
        begin(copy, keep.fbo[0], W, H);
        bindTex(0, fxTex_[*cur]);
        glUniform1i(loc(copy, "uTex"), 0);
        draw();
        if (!fxBlurPx(sigma, W, H, cur)) return false;
        const int dst = 1 - *cur;
        begin(prog, fxFbo_[dst], W, H);
        bindTex(0, keep.tex[0]);
        bindTex(1, fxTex_[*cur]);
        glUniform1i(loc(prog, "uTex"), 0);
        glUniform1i(loc(prog, "uBlur"), 1);
        glUniform4f(loc(prog, "uP"), op.p[0], op.p[1], op.p[2], op.p[3]);
        float hueTurns = std::fmod(op.q[1], 360.f) / 360.f;
        if (hueTurns < 0.f) hueTurns += 1.f;
        glUniform4f(loc(prog, "uQ"), op.p[4], op.q[0], hueTurns, op.p[5]);
        glUniform3f(loc(prog, "uCol"), ((packedCol >> 16) & 0xFF) / 255.f, ((packedCol >> 8) & 0xFF) / 255.f,
                    (packedCol & 0xFF) / 255.f);
        // The chromatic offset, the def's layer-norm formula applied literally in
        // vUv (the same norm-space treatment as Drop Shadow's position offset):
        // dir(angleee) x (strengthhh / 8) + OFT x (-1, 1) / 500.
        glUniform2f(loc(prog, "uOff"), std::cos(rad) * (op.q[5] / 8.f) - oftX / 500.f,
                    std::sin(rad) * (op.q[5] / 8.f) + oftY / 500.f);
        glUniform4f(loc(prog, "uGlowOff"), posX, posY, strX, strY);
        glUniform4f(loc(prog, "uSat"), op.q[2], op.q[3], op.q[5], 0.f);
        glUniform4f(loc(prog, "uFlags"), (flags & 1) ? 1.f : 0.f, (flags & 2) ? 1.f : 0.f,
                    (flags & 4) ? 1.f : 0.f, (float)((flags >> 5) & 3));
        glUniform2f(loc(prog, "uFlags2"), (flags & 8) ? 1.f : 0.f, (flags & 16) ? 1.f : 0.f);
        glUniform1f(loc(prog, "uBmode"), op.p[6]);
        draw();
        *cur = dst;
    }
    return true;
}

// Drop Shadow (Alight): keep the layer, blur a copy with the host gaussianblur at
// `strength`, composite (EF_DROP_SHADOW): the blurred alpha tinted by the shadow
// colour, sampled at the position offset, the layer over it.
bool Renderer::fxDropShadowAm(const FxOp& op, int W, int H, int* cur) {
    if (fxIsIdentity(op)) return true;
    const uint32_t prog = effProg(EF_DROP_SHADOW), copy = effProg(EF_COPY);
    if (!prog || !copy) return false;
    if (!ensureLevel(kFxGlowLevels - 1, W, H)) return false;
    const FxLevel& keep = lv_[kFxGlowLevels - 1];
    begin(copy, keep.fbo[0], W, H);
    bindTex(0, fxTex_[*cur]);
    glUniform1i(loc(copy, "uTex"), 0);
    draw();
    if (!fxBlurPx(fxAmGaussianSigma(op.p[3], std::hypot((float)width(), (float)height())), W, H, cur))
        return false;
    const int pk = (int)std::lround(op.p[2]);
    const float posX = (float)(pk / 4096 - 2048), posY = (float)(pk % 4096 - 2048);
    const int packedCol = (int)std::lround(std::min(16777215.f, std::max(0.f, op.p[0])));
    const int dst = 1 - *cur;
    begin(prog, fxFbo_[dst], W, H);
    bindTex(0, keep.tex[0]);
    bindTex(1, fxTex_[*cur]);
    glUniform1i(loc(prog, "uTex"), 0);
    glUniform1i(loc(prog, "uBlur"), 1);
    glUniform4f(loc(prog, "uShadow"), ((packedCol >> 16) & 0xFF) / 255.f, ((packedCol >> 8) & 0xFF) / 255.f,
                (packedCol & 0xFF) / 255.f, std::min(1.f, std::max(0.f, op.p[1])));
    glUniform2f(loc(prog, "uOff"), posX / 500.f * -1.f, posY / 500.f);
    glUniform2f(loc(prog, "uP"), op.p[4], op.p[5]);
    draw();
    *cur = dst;
    return true;
}


// ---- v177 Queue 15(f) tier-5 passes (the fx_plan.cpp twins carry the def
// citations; the GLSL mirrors them line by line).

// BB Maker (Alight): a single band-wipe composite over the layer itself.
bool Renderer::fxBbMakerAm(const FxOp& op, int W, int H, int* cur) {
    if (fxIsIdentity(op)) return true;
    const uint32_t prog = effProg(EF_BBMAKER);
    if (!prog) return false;
    const int dst = 1 - *cur;
    begin(prog, fxFbo_[dst], W, H);
    bindTex(0, fxTex_[*cur]);
    glUniform1i(loc(prog, "uTex"), 0);
    glUniform1fv(loc(prog, "uP"), 7, op.p);
    glUniform1fv(loc(prog, "uQ"), 7, op.q);
    draw();
    *cur = dst;
    return true;
}

// Unsharp Mask (Alight): keep the layer, blur a copy with the host
// gaussianblur at the effect's strength, composite (EF_UNSHARP_COMP).
bool Renderer::fxUnsharpMaskAm(const FxOp& op, int W, int H, int* cur) {
    if (fxIsIdentity(op)) return true;
    const uint32_t prog = effProg(EF_UNSHARP_COMP), copy = effProg(EF_COPY);
    if (!prog || !copy) return false;
    if (!ensureLevel(kFxGlowLevels - 1, W, H)) return false;
    const FxLevel& keep = lv_[kFxGlowLevels - 1];
    begin(copy, keep.fbo[0], W, H);
    bindTex(0, fxTex_[*cur]);
    glUniform1i(loc(copy, "uTex"), 0);
    draw();
    if (!fxBlurPx(fxAmGaussianSigma(op.p[0], std::hypot((float)width(), (float)height())), W, H, cur))
        return false;
    const int dst = 1 - *cur;
    begin(prog, fxFbo_[dst], W, H);
    bindTex(0, keep.tex[0]);
    bindTex(1, fxTex_[*cur]);
    glUniform1i(loc(prog, "uTex"), 0);
    glUniform1i(loc(prog, "uBlur"), 1);
    glUniform1fv(loc(prog, "uP"), 7, op.p);
    draw();
    *cur = dst;
    return true;
}

// New Sharpen (Alight): at half res, the horizontal streak average of the
// picture (EF_BOX_PAIR mode 1, the def's +/- pair average), then the vertical
// streak average + the sharpen composite at full res (EF_NEWSHARPEN_COMP);
// the pass list repeats `iter` times, output feeding the next round.
bool Renderer::fxNewSharpenAm(const FxOp& op, int W, int H, int* cur) {
    if (fxIsIdentity(op)) return true;
    const uint32_t prog = effProg(EF_NEWSHARPEN_COMP), box = effProg(EF_BOX_PAIR), copy = effProg(EF_COPY);
    if (!prog || !box || !copy) return false;
    const int hw = halfSize(W, 0), hh = halfSize(H, 0);
    if (!ensureLevel(0, hw, hh) || !ensureLevel(1, hw, hh) ||
        !ensureLevel(kFxGlowLevels - 1, W, H)) return false;
    const FxLevel& ds = lv_[0];
    const FxLevel& hb = lv_[1];
    const FxLevel& keep = lv_[kFxGlowLevels - 1];
    const int iter = std::min(6, std::max(1, (int)std::lround(op.p[4])));
    const float per = op.p[0] / 4.f / (float)iter;
    auto steps = [](float v) { return (int)std::min(100.01f, std::max(2.01f, v)); };
    const int nX = steps(per * (float)W * 0.5f), nY = steps(per * (float)H * 0.5f);
    for (int it = 0; it < iter; ++it) {
        begin(copy, keep.fbo[0], W, H);
        bindTex(0, fxTex_[*cur]);
        glUniform1i(loc(copy, "uTex"), 0);
        draw();
        begin(copy, ds.fbo[0], hw, hh);
        bindTex(0, fxTex_[*cur]);
        glUniform1i(loc(copy, "uTex"), 0);
        draw();
        begin(box, hb.fbo[0], hw, hh);
        bindTex(0, ds.tex[0]);
        glUniform1i(loc(box, "uTex"), 0);
        glUniform2f(loc(box, "uVel"), per, 0.f);
        glUniform1i(loc(box, "uSteps"), nX);
        glUniform1i(loc(box, "uMode"), 1);
        draw();
        const int dst = 1 - *cur;
        begin(prog, fxFbo_[dst], W, H);
        bindTex(0, keep.tex[0]);
        bindTex(1, hb.tex[0]);
        glUniform1i(loc(prog, "uTex"), 0);
        glUniform1i(loc(prog, "uBlur"), 1);
        glUniform2f(loc(prog, "uVel"), 0.f, per);
        glUniform1i(loc(prog, "uSteps"), nY);
        glUniform1fv(loc(prog, "uP"), 7, op.p);
        draw();
        *cur = dst;
    }
    return true;
}

// The blend-generator family (Alight): Gradient Shadow, Gradient Flow, Partx,
// Edge Line, Light Sweep, 3 Color Gradient — one program (EF_AM_BLENDGEN),
// uKind selects the def, exactly as the CPU twin's kind.
bool Renderer::fxAmBlendGenAm(const FxOp& op, int W, int H, int* cur) {
    if (fxIsIdentity(op)) return true;
    const uint32_t prog = effProg(EF_AM_BLENDGEN);
    if (!prog) return false;
    const int kind = op.type == FX_GRADSHADOW_AM ? 0
                   : op.type == FX_GRADIENTFLOW_AM ? 1
                   : op.type == FX_PARTX_AM ? 2
                   : op.type == FX_EDGELINE_AM ? 3
                   : op.type == FX_LIGHTSWEEP_AM ? 4 : 5;
    const int dst = 1 - *cur;
    begin(prog, fxFbo_[dst], W, H);
    bindTex(0, fxTex_[*cur]);
    glUniform1i(loc(prog, "uTex"), 0);
    glUniform1fv(loc(prog, "uP"), 7, op.p);
    glUniform1fv(loc(prog, "uQ"), 7, op.q);
    glUniform1fv(loc(prog, "uR"), 7, op.r);
    glUniform1i(loc(prog, "uKind"), kind);
    glUniform2f(loc(prog, "uPic"), (float)W, (float)H);
    draw();
    *cur = dst;
    return true;
}

// Deep Glow v1 (Alight): keep the layer, gaussianblur a copy (the def's
// vblur, sampled at the position offset) and boxblur2 a second copy (its
// hblur, feeding only the sshadow silhouette), composite (EF_DEEP_GLOW1_COMP).
bool Renderer::fxDeepGlow1Am(const FxOp& op, int W, int H, int* cur) {
    if (fxIsIdentity(op)) return true;
    const uint32_t prog = effProg(EF_DEEP_GLOW1_COMP), box = effProg(EF_BOX_PAIR), copy = effProg(EF_COPY);
    if (!prog || !box || !copy) return false;
    for (int i = 0; i < 3; ++i) if (!ensureLevel(i, W, H)) return false;
    if (!ensureLevel(kFxGlowLevels - 1, W, H)) return false;
    const FxLevel& keep = lv_[kFxGlowLevels - 1];
    begin(copy, keep.fbo[0], W, H);
    bindTex(0, fxTex_[*cur]);
    glUniform1i(loc(copy, "uTex"), 0);
    draw();
    const float diag = std::hypot((float)width(), (float)height());
    if (!fxBlurPx(fxAmGaussianSigma(op.p[0], diag), W, H, cur)) return false;
    begin(copy, lv_[0].fbo[0], W, H);
    bindTex(0, fxTex_[*cur]);
    glUniform1i(loc(copy, "uTex"), 0);
    draw();
    const int iter = std::min(6, std::max(1, (int)std::lround(op.r[4])));
    const float per = op.p[0] / 4.f / (float)iter;
    begin(box, lv_[1].fbo[0], W, H);
    bindTex(0, keep.tex[0]);
    glUniform1i(loc(box, "uTex"), 0);
    glUniform2f(loc(box, "uVel"), per, 0.f);
    glUniform1i(loc(box, "uSteps"), fxBoxSamples(per * (float)W, false));
    glUniform1i(loc(box, "uMode"), 0);
    draw();
    begin(box, lv_[2].fbo[0], W, H);
    bindTex(0, lv_[1].tex[0]);
    glUniform1i(loc(box, "uTex"), 0);
    glUniform2f(loc(box, "uVel"), 0.f, per);
    glUniform1i(loc(box, "uSteps"), fxBoxSamples(per * (float)H, false));
    glUniform1i(loc(box, "uMode"), 0);
    draw();
    const int pk = (int)std::lround(op.q[4]);
    const float posX = (float)(pk / 4096 - 2048), posY = (float)(pk % 4096 - 2048);
    const int dst = 1 - *cur;
    begin(prog, fxFbo_[dst], W, H);
    bindTex(0, keep.tex[0]);
    bindTex(1, lv_[0].tex[0]);
    bindTex(2, lv_[2].tex[0]);
    glUniform1i(loc(prog, "uTex"), 0);
    glUniform1i(loc(prog, "uBlur"), 1);
    glUniform1i(loc(prog, "uHblur"), 2);
    glUniform1fv(loc(prog, "uP"), 7, op.p);
    glUniform1fv(loc(prog, "uQ"), 7, op.q);
    glUniform1fv(loc(prog, "uR"), 7, op.r);
    glUniform2f(loc(prog, "uOff"), (posX / 15.f) * -1.f / 500.f, (posY / 15.f) / 500.f);
    draw();
    *cur = dst;
    return true;
}

// Spill Cleanup (Alight): gaussianblur the layer, fold the ordered streak
// accumulation over it twice (EF_SPILL_STREAK passes composited with
// EF_SPILL_OVER), then the luma-replaced spill composite (EF_SPILL_FINAL).
bool Renderer::fxSpillCleanupAm(const FxOp& op, int W, int H, int* cur) {
    const uint32_t streak = effProg(EF_SPILL_STREAK), over = effProg(EF_SPILL_OVER),
                   fin = effProg(EF_SPILL_FINAL), copy = effProg(EF_COPY);
    if (!streak || !over || !fin || !copy) return false;
    for (int i = 0; i < 5; ++i) if (!ensureLevel(i, W, H)) return false;
    if (!ensureLevel(kFxGlowLevels - 1, W, H)) return false;
    const FxLevel& keep = lv_[kFxGlowLevels - 1];
    begin(copy, keep.fbo[0], W, H);
    bindTex(0, fxTex_[*cur]);
    glUniform1i(loc(copy, "uTex"), 0);
    draw();
    if (!fxBlurPx(fxAmGaussianSigma(op.p[0], std::hypot((float)width(), (float)height())), W, H, cur))
        return false;
    begin(copy, lv_[0].fbo[0], W, H);
    bindTex(0, fxTex_[*cur]);
    glUniform1i(loc(copy, "uTex"), 0);
    draw();
    const float dist = op.p[0] * 0.05f;
    auto steps = [](float v) { return (int)std::min(100.01f, std::max(1.01f, v)); };
    const int nX = steps(dist * (float)W), nY = steps(dist * (float)H);
    auto streakInto = [&](int srcLvl, int dstLvl, bool horiz) {
        begin(streak, lv_[dstLvl].fbo[0], W, H);
        bindTex(0, lv_[srcLvl].tex[0]);
        glUniform1i(loc(streak, "uTex"), 0);
        glUniform2f(loc(streak, "uAxis"), horiz ? 1.f : 0.f, horiz ? 0.f : 1.f);
        glUniform1f(loc(streak, "uDist"), dist);
        glUniform1i(loc(streak, "uSteps"), horiz ? nX : nY);
        draw();
    };
    auto overInto = [&](int bottomLvl, int topLvl, int dstLvl) {
        begin(over, lv_[dstLvl].fbo[0], W, H);
        bindTex(0, lv_[bottomLvl].tex[0]);
        bindTex(1, lv_[topLvl].tex[0]);
        glUniform1i(loc(over, "uBottom"), 0);
        glUniform1i(loc(over, "uTop"), 1);
        draw();
    };
    streakInto(0, 1, true);
    streakInto(0, 2, false);
    overInto(1, 2, 3);
    overInto(3, 0, 1);   // hpass in lv_[1]
    streakInto(1, 2, true);
    streakInto(1, 3, false);
    overInto(2, 3, 4);
    overInto(4, 0, 2);   // vpass in lv_[2]
    const int dst = 1 - *cur;
    begin(fin, fxFbo_[dst], W, H);
    bindTex(0, keep.tex[0]);
    bindTex(1, lv_[0].tex[0]);
    bindTex(2, lv_[2].tex[0]);
    glUniform1i(loc(fin, "uBase"), 0);
    glUniform1i(loc(fin, "uBlur"), 1);
    glUniform1i(loc(fin, "uStreak"), 2);
    draw();
    *cur = dst;
    return true;
}

// Extrude / Extrude Flat (Alight): keep the layer, blur a copy for the march's alpha
// buffer (Extrude only — its gaussianblur buffer pass, strength through the
// gaussianblur kernel (fxAmGaussianSigma)), then the raymarch pass. The orientation matrix and
// the packed-slot unpacking mirror the CPU twin (fxExtrudeAmPixel) exactly.
bool Renderer::fxExtrudeAm(const FxOp& op, int W, int H, int* cur) {
    const bool flat = op.type == FX_EXTRUDE_FLAT_AM;
    const uint32_t prog = effProg(EF_EXTRUDE), copy = effProg(EF_COPY);
    if (!prog || !copy) return false;
    if (!ensureLevel(kFxGlowLevels - 1, W, H)) return false;
    const FxLevel& keep = lv_[kFxGlowLevels - 1];
    begin(copy, keep.fbo[0], W, H);
    bindTex(0, fxTex_[*cur]);
    glUniform1i(loc(copy, "uTex"), 0);
    draw();
    auto unpack = [](float packedF, float* r, float* g, float* b) {
        const int pk = (int)std::lround(std::min(16777215.f, std::max(0.f, packedF)));
        *r = ((pk >> 16) & 0xFF) / 255.f;
        *g = ((pk >> 8) & 0xFF) / 255.f;
        *b = (pk & 0xFF) / 255.f;
    };
    float inR, inG, inB, faR, faG, faB, liR, liG, liB;
    unpack(op.q[6], &inR, &inG, &inB);
    unpack(op.r[0], &faR, &faG, &faB);
    float strength = 0.1f, iter = 300.f;
    float lightIntensity = 0.f, shine = 0.f, spec = 0.f, ambient = 0.f;
    float lightPos[3] = {0.f, 0.f, 0.f};
    liR = liG = liB = 0.f;
    if (flat) {
        iter = std::max(1.f, op.r[1]);
        strength = 0.f;   // extrudeflat's def has no strength: its step-size factor is 1.0
    } else {
        unpack(op.r[1], &liR, &liG, &liB);
        const int pq = (int)std::lround(std::min(16777215.f, std::max(0.f, op.r[2])));
        const float ldx = (float)(pq / 4096 - 2048), ldy = (float)(pq % 4096 - 2048);
        const int li = (int)std::lround(op.r[3]);
        lightIntensity = (li % 256) / 100.f;
        strength = (li / 256) / 100.f;
        const int sa = (int)std::lround(op.r[4]);
        shine = (sa / 128) / 100.f;
        ambient = (sa % 128) / 100.f;
        spec = op.r[5];
        const int dm = (int)std::lround(op.r[6]);
        const float lightDepth = (dm % 2048) / 2.f - 100.f;
        iter = std::max(1, dm / 2048);
        lightPos[0] = ldx / 2.f;
        lightPos[1] = -ldy / 2.f;
        lightPos[2] = -lightDepth * 2.f;
        if (!fxBlurPx(fxAmGaussianSigma(strength, std::hypot((float)width(), (float)height())), W, H, cur))
            return false;   // AM strength -> the host gaussianblur sigma (the def's kernel)
    }
    float m[9];
    fxExtrudeRotateMat(op.q[0], op.q[1], op.q[2], m);
    float matCol[9];  // glUniformMatrix3fv takes column-major
    for (int col = 0; col < 3; ++col)
        for (int row = 0; row < 3; ++row) matCol[col * 3 + row] = m[row * 3 + col];
    const float mx = std::max((float)W, (float)H);
    const int dst = 1 - *cur;
    begin(prog, fxFbo_[dst], W, H);
    bindTex(0, keep.tex[0]);
    bindTex(1, flat ? keep.tex[0] : fxTex_[*cur]);
    glUniform1i(loc(prog, "uTex"), 0);
    glUniform1i(loc(prog, "uBlur"), 1);
    glUniform2f(loc(prog, "uSize"), (float)W, (float)H);
    glUniformMatrix3fv(loc(prog, "uMat"), 1, GL_FALSE, matCol);
    glUniform3f(loc(prog, "uCenter"), op.q[3] / 100.f, op.q[4] / 100.f, op.q[5] / 100.f);
    glUniform3f(loc(prog, "uBox"), op.p[2] * 0.5f * W / mx, op.p[3] * 0.5f * H / mx, op.p[0]);
    glUniform3f(loc(prog, "uMisc"), op.p[1], strength, iter);
    glUniform4f(loc(prog, "uBlend"), op.p[4], op.p[5], op.p[6], flat ? 1.f : 0.f);
    glUniform4f(loc(prog, "uInterior"), inR, inG, inB, 1.f);
    glUniform4f(loc(prog, "uFade"), faR, faG, faB, 1.f);
    glUniform4f(loc(prog, "uLightA"), lightIntensity, shine, spec, ambient);
    glUniform3f(loc(prog, "uLightColor"), liR, liG, liB);
    glUniform3f(loc(prog, "uLightPos"), lightPos[0], lightPos[1], lightPos[2]);
    draw();
    *cur = dst;
    return true;
}

bool Renderer::fxSplit(const FxOp& op, int W, int H, int* cur) {
    const float px = fxSplitPixels(op.p[0], (float)H / 1080.f);
    if (px < 0.05f) return true;
    const uint32_t prog = effProg(EF_SPLIT);
    if (!prog) return false;
    const float ang = op.p[1] * 3.14159265f / 180.f;
    const float radial = std::min(100.f, std::max(0.f, op.p[2])) / 100.f;
    const int dst = 1 - *cur;
    begin(prog, fxFbo_[dst], W, H);
    bindTex(0, fxTex_[*cur]);
    glUniform1i(loc(prog, "uTex"), 0);
    glUniform2f(loc(prog, "uTexel"), 1.f / W, 1.f / H);
    glUniform1f(loc(prog, "uPx"), px);
    glUniform2f(loc(prog, "uDir"), std::cos(ang), std::sin(ang));
    glUniform1f(loc(prog, "uRadial"), radial);
    glUniform1f(loc(prog, "uAspect"), (float)W / (float)H);
    draw();
    *cur = dst;
    return true;
}

bool Renderer::fxGrain(const FxOp& op, int W, int H, int* cur, int frame) {
    const float amount = std::min(100.f, std::max(0.f, op.p[0])) / 100.f;
    if (amount <= 0.f) return true;
    const uint32_t prog = effProg(EF_GRAIN);
    if (!prog) return false;
    const int dst = 1 - *cur;
    begin(prog, fxFbo_[dst], W, H);
    bindTex(0, fxTex_[*cur]);
    glUniform1i(loc(prog, "uTex"), 0);
    glUniform1f(loc(prog, "uCell"), fxGrainCell(op.p[1], (float)H / 1080.f));
    glUniform1f(loc(prog, "uAmount"), amount);
    glUniform1f(loc(prog, "uColor"), std::min(100.f, std::max(0.f, op.p[2])) / 100.f);
    glUniform1i(loc(prog, "uFrame"), frame);
    draw();
    *cur = dst;
    return true;
}

bool Renderer::fxVignette(const FxOp& op, int W, int H, int* cur) {
    const float amount = std::min(100.f, std::max(0.f, op.p[0])) / 100.f;
    if (amount <= 0.f) return true;
    const uint32_t prog = effProg(EF_VIG);
    if (!prog) return false;
    const float size = std::min(100.f, std::max(0.f, op.p[1])) / 100.f;
    const float soft = std::min(100.f, std::max(0.f, op.p[2])) / 100.f;
    const float inner = 0.15f + 0.65f * size;
    const float outer = std::min(1.25f, inner + 0.1f + 0.9f * soft);
    const int dst = 1 - *cur;
    begin(prog, fxFbo_[dst], W, H);
    bindTex(0, fxTex_[*cur]);
    glUniform1i(loc(prog, "uTex"), 0);
    glUniform1f(loc(prog, "uAmount"), amount);
    glUniform2f(loc(prog, "uEdge"), inner, outer);
    glUniform1f(loc(prog, "uAspect"), (float)W / (float)H);
    draw();
    *cur = dst;
    return true;
}

bool Renderer::fxCopyBg(const FxOp& op, int W, int H, int* cur) {
    const float amount = std::min(100.f, std::max(0.f, op.p[0])) / 100.f;
    if (amount <= 0.f || !bgOk_) return true;  // nothing to copy (or this layer is not placed like its picture): left as it is
    const uint32_t prog = effProg(EF_COPYBG);
    if (!prog) return false;
    const int dst = 1 - *cur;
    begin(prog, fxFbo_[dst], W, H);
    bindTex(0, fxTex_[*cur]);
    bindTex(1, texBackdrop_);
    glUniform1i(loc(prog, "uTex"), 0);
    glUniform1i(loc(prog, "uBg"), 1);
    glUniform4f(loc(prog, "uRect"), bgRect_[0], bgRect_[1], bgRect_[2], bgRect_[3]);
    glUniform1f(loc(prog, "uAmount"), amount);
    draw();
    *cur = dst;
    return true;
}

// Displace Map (Alight): one pass sampling the backdrop; like Copy Background it is a
// no-op when the backdrop was not captured for this layer (bgOk_).
bool Renderer::fxDisplaceMapAm(const FxOp& op, int W, int H, int* cur) {
    if (!bgOk_) return true;
    const uint32_t prog = effProg(EF_DISPLACE_MAP);
    if (!prog) return false;
    const int dst = 1 - *cur;
    begin(prog, fxFbo_[dst], W, H);
    bindTex(0, fxTex_[*cur]);
    bindTex(1, texBackdrop_);
    glUniform1i(loc(prog, "uTex"), 0);
    glUniform1i(loc(prog, "uBg"), 1);
    glUniform4f(loc(prog, "uRect"), bgRect_[0], bgRect_[1], bgRect_[2], bgRect_[3]);
    glUniform1fv(loc(prog, "uP"), 7, op.p);
    glUniform1fv(loc(prog, "uQ"), 7, op.q);
    glUniform1i(loc(prog, "uRadial"), op.type == FX_DISPLACE_MAP_RAD_AM ? 1 : 0);
    glUniform2f(loc(prog, "uScreen"), (float)w_, (float)h_);
    glUniform1f(loc(prog, "uLayerAspect"), H > 0 ? (float)W / (float)H : 1.f);
    draw();
    *cur = dst;
    return true;
}

// Smooth Bevel (Alight): pass 0 writes the X-distance buffer into an lv_ level, pass 1
// shades from it. op.p = [radius, angle_d, angle_s, intensity_d, intensity_s,
// elevation_d, elevation_s], op.q = [spec, colour_d packed, colour_s packed].
bool Renderer::fxSmoothBevelAm(const FxOp& op, int W, int H, int* cur) {
    if (fxIsIdentity(op)) return true;
    const uint32_t prog = effProg(EF_SMOOTH_BEVEL);
    if (!prog) return false;
    if (!ensureLevel(kFxGlowLevels - 1, W, H)) return false;
    const FxLevel& buf = lv_[kFxGlowLevels - 1];
    const float radius2 = op.p[0] * std::sqrt((float)W * (float)W + (float)H * (float)H) /
                          std::sqrt(2.f * 1024.f * 1024.f);
    begin(prog, buf.fbo[0], W, H);
    bindTex(0, fxTex_[*cur]);
    glUniform1i(loc(prog, "uTex"), 0);
    glUniform2f(loc(prog, "uTexel"), 1.f / (float)W, 1.f / (float)H);
    glUniform1f(loc(prog, "uRadius2"), radius2);
    glUniform1i(loc(prog, "uPass"), 0);
    draw();
    const int dst = 1 - *cur;
    begin(prog, fxFbo_[dst], W, H);
    bindTex(0, fxTex_[*cur]);
    bindTex(1, buf.tex[0]);
    glUniform1i(loc(prog, "uTex"), 0);
    glUniform1i(loc(prog, "uBuf"), 1);
    glUniform2f(loc(prog, "uTexel"), 1.f / (float)W, 1.f / (float)H);
    glUniform1f(loc(prog, "uRadius2"), radius2);
    glUniform1i(loc(prog, "uPass"), 1);
    // shader uP: [angle_s, angle_d, elev_s, elev_d, spec, intensity_s, intensity_d]
    const float pp[7] = {op.p[2], op.p[1], op.p[6], op.p[5], op.q[0], op.p[4], op.p[3]};
    glUniform1fv(loc(prog, "uP"), 7, pp);
    auto unpack3 = [](float packedF, float* r, float* g, float* b) {
        const int pk = (int)std::lround(std::min(16777215.f, std::max(0.f, packedF)));
        *r = ((pk >> 16) & 0xFF) / 255.f;
        *g = ((pk >> 8) & 0xFF) / 255.f;
        *b = (pk & 0xFF) / 255.f;
    };
    float r, g, b;
    unpack3(op.q[2], &r, &g, &b);
    glUniform3f(loc(prog, "uColS"), r, g, b);
    unpack3(op.q[1], &r, &g, &b);
    glUniform3f(loc(prog, "uColD"), r, g, b);
    draw();
    *cur = dst;
    return true;
}

// Inner Glow (Alight): pass 0 writes the X-distance buffer into an lv_ level, pass 1
// shades from it. op.p = [radius, alpha, hardness, colour packed, blend mode].
bool Renderer::fxInnerGlowAm(const FxOp& op, int W, int H, int* cur) {
    if (fxIsIdentity(op)) return true;
    const uint32_t prog = effProg(EF_INNER_GLOW);
    if (!prog) return false;
    if (!ensureLevel(kFxGlowLevels - 1, W, H)) return false;
    const FxLevel& buf = lv_[kFxGlowLevels - 1];
    const float radius2 = op.p[0] * std::sqrt((float)W * (float)W + (float)H * (float)H) /
                          std::sqrt(2.f * 1024.f * 1024.f);
    const float stride = fxInnerGlowAmStride(radius2);
    begin(prog, buf.fbo[0], W, H);
    bindTex(0, fxTex_[*cur]);
    glUniform1i(loc(prog, "uTex"), 0);
    glUniform2f(loc(prog, "uTexel"), 1.f / (float)W, 1.f / (float)H);
    glUniform1f(loc(prog, "uRadius2"), radius2);
    glUniform1f(loc(prog, "uStride"), stride);
    glUniform1i(loc(prog, "uPass"), 0);
    draw();
    const int dst = 1 - *cur;
    begin(prog, fxFbo_[dst], W, H);
    bindTex(0, fxTex_[*cur]);
    bindTex(1, buf.tex[0]);
    glUniform1i(loc(prog, "uTex"), 0);
    glUniform1i(loc(prog, "uBuf"), 1);
    glUniform2f(loc(prog, "uTexel"), 1.f / (float)W, 1.f / (float)H);
    glUniform1f(loc(prog, "uRadius2"), radius2);
    glUniform1f(loc(prog, "uStride"), stride);
    glUniform1i(loc(prog, "uPass"), 1);
    // shader uP: [alpha, hardness, blend mode]
    const float pp[7] = {op.p[1], op.p[2], op.p[4], 0.f, 0.f, 0.f, 0.f};
    glUniform1fv(loc(prog, "uP"), 7, pp);
    const int pk = (int)std::lround(std::min(16777215.f, std::max(0.f, op.p[3])));
    glUniform3f(loc(prog, "uColor"), ((pk >> 16) & 0xFF) / 255.f, ((pk >> 8) & 0xFF) / 255.f,
                (pk & 0xFF) / 255.f);
    draw();
    *cur = dst;
    return true;
}

// ---- v175 tier-4 dedicated passes ----

// Channel Map (Alight): one pass; op.p[0..3] are the R / G / B / A channel sources.
bool Renderer::fxChannelMapAm(const FxOp& op, int W, int H, int* cur) {
    if (fxIsIdentity(op)) return true;
    const uint32_t prog = effProg(EF_CHANNEL_MAP);
    if (!prog) return false;
    const int dst = 1 - *cur;
    begin(prog, fxFbo_[dst], W, H);
    bindTex(0, fxTex_[*cur]);
    glUniform1i(loc(prog, "uTex"), 0);
    glUniform1fv(loc(prog, "uP"), 7, op.p);
    draw();
    *cur = dst;
    return true;
}

// Mosaic (Alight): the x-average pass into the keep level, then the y pass.
// op.p = [size, phase x, phase y].
bool Renderer::fxMosaicAm(const FxOp& op, int W, int H, int* cur) {
    if (fxIsIdentity(op)) return true;
    const uint32_t prog = effProg(EF_MOSAIC_AM);
    if (!prog) return false;
    if (!ensureLevel(kFxGlowLevels - 1, W, H)) return false;
    const FxLevel& hb = lv_[kFxGlowLevels - 1];
    auto setCommon = [&]() {
        glUniform3f(loc(prog, "uP"), op.p[0], op.p[1], op.p[2]);
        glUniform2f(loc(prog, "uSize"), (float)W, (float)H);
    };
    begin(prog, hb.fbo[0], W, H);
    bindTex(0, fxTex_[*cur]);
    glUniform1i(loc(prog, "uTex"), 0);
    glUniform1i(loc(prog, "uPass"), 0);
    setCommon();
    draw();
    const int dst = 1 - *cur;
    begin(prog, fxFbo_[dst], W, H);
    bindTex(0, fxTex_[*cur]);
    bindTex(1, hb.tex[0]);
    glUniform1i(loc(prog, "uTex"), 0);
    glUniform1i(loc(prog, "uHbuf"), 1);
    glUniform1i(loc(prog, "uPass"), 1);
    setCommon();
    draw();
    *cur = dst;
    return true;
}

// Box Blur 3 (Alight): half-res symmetric averages, x then y. op.p = [strength].
bool Renderer::fxBoxBlur3Am(const FxOp& op, int W, int H, int* cur) {
    if (fxIsIdentity(op)) return true;
    const uint32_t prog = effProg(EF_BOX_PAIR), copy = effProg(EF_COPY);
    if (!prog || !copy) return false;
    const int hw = halfSize(W, 0), hh = halfSize(H, 0);
    if (!ensureLevel(0, hw, hh) || !ensureLevel(1, hw, hh)) return false;
    const FxLevel& ds = lv_[0];
    const FxLevel& hb = lv_[1];
    const float sn = op.p[0] / 10.f;
    begin(copy, ds.fbo[0], hw, hh);
    bindTex(0, fxTex_[*cur]);
    glUniform1i(loc(copy, "uTex"), 0);
    draw();
    begin(prog, hb.fbo[0], hw, hh);
    bindTex(0, ds.tex[0]);
    glUniform1i(loc(prog, "uTex"), 0);
    glUniform2f(loc(prog, "uVel"), sn, 0.f);
    glUniform1i(loc(prog, "uSteps"), fxBoxSamples(sn * (float)hw, true));
    glUniform1i(loc(prog, "uMode"), 1);
    draw();
    const int dst = 1 - *cur;
    begin(prog, fxFbo_[dst], W, H);
    bindTex(0, hb.tex[0]);
    glUniform1i(loc(prog, "uTex"), 0);
    glUniform2f(loc(prog, "uVel"), 0.f, sn);
    glUniform1i(loc(prog, "uSteps"), fxBoxSamples(sn * (float)hh, true));
    glUniform1i(loc(prog, "uMode"), 1);
    draw();
    *cur = dst;
    return true;
}

// Inner Blur (Alight): iter chains of boxblur2 (per-pass strength / 4 / iter) over
// the fx ping-pong, each chain composited over its kept input. op.p = [strength, iter].
bool Renderer::fxInnerBlurAm(const FxOp& op, int W, int H, int* cur) {
    if (fxIsIdentity(op)) return true;
    const uint32_t prog = effProg(EF_BOX_PAIR), copy = effProg(EF_COPY);
    if (!prog || !copy) return false;
    if (!ensureLevel(kFxGlowLevels - 1, W, H)) return false;
    const FxLevel& keep = lv_[kFxGlowLevels - 1];
    const int iter = std::min(6, std::max(1, (int)op.p[1]));
    const float per = op.p[0] / 4.f / (float)iter;
    const int nX = fxBoxSamples(per * (float)W, false);
    const int nY = fxBoxSamples(per * (float)H, false);
    for (int it = 0; it < iter; ++it) {
        begin(copy, keep.fbo[0], W, H);
        bindTex(0, fxTex_[*cur]);
        glUniform1i(loc(copy, "uTex"), 0);
        draw();
        int dst = 1 - *cur;   // horizontal boxblur2 pass
        begin(prog, fxFbo_[dst], W, H);
        bindTex(0, keep.tex[0]);
        glUniform1i(loc(prog, "uTex"), 0);
        glUniform2f(loc(prog, "uVel"), per, 0.f);
        glUniform1i(loc(prog, "uSteps"), nX);
        glUniform1i(loc(prog, "uMode"), 0);
        draw();
        *cur = dst;
        dst = 1 - *cur;       // vertical boxblur2 pass (velocity (0, -per) in AM; the span is symmetric)
        begin(prog, fxFbo_[dst], W, H);
        bindTex(0, fxTex_[*cur]);
        glUniform1i(loc(prog, "uTex"), 0);
        glUniform2f(loc(prog, "uVel"), 0.f, per);
        glUniform1i(loc(prog, "uSteps"), nY);
        glUniform1i(loc(prog, "uMode"), 0);
        draw();
        *cur = dst;
        dst = 1 - *cur;       // composite over the chain input
        begin(prog, fxFbo_[dst], W, H);
        bindTex(0, fxTex_[*cur]);
        bindTex(1, keep.tex[0]);
        glUniform1i(loc(prog, "uTex"), 0);
        glUniform1i(loc(prog, "uBase"), 1);
        glUniform1i(loc(prog, "uMode"), 2);
        draw();
        *cur = dst;
    }
    return true;
}

// Feather (Alight): iter chains of the quarter-res symmetric averages; each chain's
// output is its input times the blurred alpha squared. op.p = [strength, iter].
bool Renderer::fxFeatherAm(const FxOp& op, int W, int H, int* cur) {
    if (fxIsIdentity(op)) return true;
    const uint32_t prog = effProg(EF_BOX_PAIR), copy = effProg(EF_COPY);
    if (!prog || !copy) return false;
    const int qw = halfSize(W, 1), qh = halfSize(H, 1);
    if (!ensureLevel(0, qw, qh) || !ensureLevel(1, qw, qh)) return false;
    const FxLevel& ds = lv_[0];
    const FxLevel& hb = lv_[1];
    const int iter = std::min(5, std::max(1, (int)op.p[1]));
    const float sn = op.p[0] / 4.f / (float)iter;
    const int nX = fxBoxSamples(sn * (float)qw, true);
    const int nY = fxBoxSamples(sn * (float)qh, true);
    for (int it = 0; it < iter; ++it) {
        begin(copy, ds.fbo[0], qw, qh);
        bindTex(0, fxTex_[*cur]);
        glUniform1i(loc(copy, "uTex"), 0);
        draw();
        begin(prog, hb.fbo[0], qw, qh);
        bindTex(0, ds.tex[0]);
        glUniform1i(loc(prog, "uTex"), 0);
        glUniform2f(loc(prog, "uVel"), sn, 0.f);
        glUniform1i(loc(prog, "uSteps"), nX);
        glUniform1i(loc(prog, "uMode"), 1);
        draw();
        const int dst = 1 - *cur;   // vertical pass + the alpha-squared composite
        begin(prog, fxFbo_[dst], W, H);
        bindTex(0, hb.tex[0]);
        bindTex(1, fxTex_[*cur]);
        glUniform1i(loc(prog, "uTex"), 0);
        glUniform1i(loc(prog, "uBase"), 1);
        glUniform2f(loc(prog, "uVel"), 0.f, sn);
        glUniform1i(loc(prog, "uSteps"), nY);
        glUniform1i(loc(prog, "uMode"), 3);
        draw();
        *cur = dst;
    }
    return true;
}

// Glow (Alight, the outer glow): Inner Glow's scan with the inverted composite.
// Blend modes 1 / 2 read the backdrop; when none was captured they fall back to
// mode 0 (the colour unmodulated) rather than dropping the glow. op.p = [radius,
// alpha, hardness, colour packed, colour alpha, blend mode].
bool Renderer::fxGlowOuterAm(const FxOp& op, int W, int H, int* cur) {
    if (fxIsIdentity(op)) return true;
    const uint32_t prog = effProg(EF_GLOW_OUTER);
    if (!prog) return false;
    const float radius2 = op.p[0] * std::sqrt((float)W * (float)W + (float)H * (float)H) /
                          std::sqrt(2.f * 1024.f * 1024.f);
    if (radius2 < 0.5f) return true;   // AM: a plain copy
    if (!ensureLevel(kFxGlowLevels - 1, W, H)) return false;
    const FxLevel& buf = lv_[kFxGlowLevels - 1];
    const float stride = fxInnerGlowAmStride(radius2);
    begin(prog, buf.fbo[0], W, H);
    bindTex(0, fxTex_[*cur]);
    glUniform1i(loc(prog, "uTex"), 0);
    glUniform2f(loc(prog, "uTexel"), 1.f / (float)W, 1.f / (float)H);
    glUniform1f(loc(prog, "uRadius2"), radius2);
    glUniform1f(loc(prog, "uStride"), stride);
    glUniform1i(loc(prog, "uPass"), 0);
    draw();
    const int bm = (int)op.p[5];
    const int effBm = (bm == 1 || bm == 2) && bgOk_ ? bm : 0;
    const int dst = 1 - *cur;
    begin(prog, fxFbo_[dst], W, H);
    bindTex(0, fxTex_[*cur]);
    bindTex(1, buf.tex[0]);
    bindTex(2, effBm == 0 ? fxTex_[*cur] : texBackdrop_);
    glUniform1i(loc(prog, "uTex"), 0);
    glUniform1i(loc(prog, "uBuf"), 1);
    glUniform1i(loc(prog, "uComp"), 2);
    glUniform4f(loc(prog, "uRect"), bgRect_[0], bgRect_[1], bgRect_[2], bgRect_[3]);
    glUniform2f(loc(prog, "uTexel"), 1.f / (float)W, 1.f / (float)H);
    glUniform1f(loc(prog, "uRadius2"), radius2);
    glUniform1f(loc(prog, "uStride"), stride);
    glUniform1i(loc(prog, "uPass"), 1);
    const float pp[7] = {op.p[1], op.p[2], (float)effBm, 0.f, 0.f, 0.f, 0.f};
    glUniform1fv(loc(prog, "uP"), 7, pp);
    const int pk = (int)std::lround(std::min(16777215.f, std::max(0.f, op.p[3])));
    glUniform3f(loc(prog, "uColor"), ((pk >> 16) & 0xFF) / 255.f, ((pk >> 8) & 0xFF) / 255.f,
                (pk & 0xFF) / 255.f);
    glUniform1f(loc(prog, "uColorA"), op.p[4]);
    draw();
    *cur = dst;
    return true;
}

// Electric Edges (Alight): keep the layer, blur the current target at the def's
// fixed strength (0.42 through the gaussianblur calibration) for blurBuf, then the
// main pass. Slots per fx_plan.h (p / q / r tiers).
bool Renderer::fxElectricEdgesAm(const FxOp& op, int W, int H, int* cur) {
    if (fxIsIdentity(op)) return true;
    const uint32_t prog = effProg(EF_ELECTRIC_AM), copy = effProg(EF_COPY);
    if (!prog || !copy) return false;
    if (!ensureLevel(kFxGlowLevels - 1, W, H)) return false;
    const FxLevel& keep = lv_[kFxGlowLevels - 1];
    begin(copy, keep.fbo[0], W, H);
    bindTex(0, fxTex_[*cur]);
    glUniform1i(loc(copy, "uTex"), 0);
    draw();
    if (!fxBlurPx(fxAmGaussianSigma(op.r[6], std::hypot((float)width(), (float)height())), W, H, cur))
        return false;   // the def's hblur strength through the gaussianblur kernel
    auto unpack = [](float packedF, float* r, float* g, float* b) {
        const int pk = (int)std::lround(std::min(16777215.f, std::max(0.f, packedF)));
        *r = ((pk >> 16) & 0xFF) / 255.f;
        *g = ((pk >> 8) & 0xFF) / 255.f;
        *b = (pk & 0xFF) / 255.f;
    };
    float c1r, c1g, c1b, c2r, c2g, c2b, mcr, mcg, mcb;
    unpack(op.p[4], &c1r, &c1g, &c1b);
    unpack(op.p[5], &c2r, &c2g, &c2b);
    unpack(op.r[1], &mcr, &mcg, &mcb);
    const int dst = 1 - *cur;
    begin(prog, fxFbo_[dst], W, H);
    bindTex(0, keep.tex[0]);
    bindTex(1, fxTex_[*cur]);
    glUniform1i(loc(prog, "uTex"), 0);
    glUniform1i(loc(prog, "uBlur"), 1);
    glUniform2f(loc(prog, "uTexel"), 1.f / (float)W, 1.f / (float)H);
    glUniform1f(loc(prog, "uAspect"), (float)W / (float)H);
    glUniform1fv(loc(prog, "uP"), 7, op.p);
    glUniform1fv(loc(prog, "uQ"), 7, op.q);
    glUniform1fv(loc(prog, "uR"), 7, op.r);
    glUniform3f(loc(prog, "uC1"), c1r, c1g, c1b);
    glUniform3f(loc(prog, "uC2"), c2r, c2g, c2b);
    glUniform3f(loc(prog, "uMaskCol"), mcr, mcg, mcb);
    draw();
    *cur = dst;
    return true;
}

// Smooth Edges (Alight): keep the input and a gaussian-blurred copy of it (the
// def's hblur, strength through the gaussianblur kernel), run the two streak
// composites through lv_ levels, then the final alpha cut.
bool Renderer::fxSmoothEdgesAm(const FxOp& op, int W, int H, int* cur) {
    if (fxIsIdentity(op)) return true;
    const uint32_t prog = effProg(EF_SMOOTH_EDGES_AM), copy = effProg(EF_COPY);
    if (!prog || !copy) return false;
    if (!ensureLevel(0, W, H) || !ensureLevel(1, W, H) || !ensureLevel(2, W, H) ||
        !ensureLevel(3, W, H)) return false;
    const FxLevel& hs = lv_[0];
    const FxLevel& vs = lv_[1];
    const FxLevel& inKeep = lv_[2];
    const FxLevel& blurKeep = lv_[3];
    begin(copy, inKeep.fbo[0], W, H);
    bindTex(0, fxTex_[*cur]);
    glUniform1i(loc(copy, "uTex"), 0);
    draw();
    if (!fxBlurPx(fxAmGaussianSigma(op.p[0], std::hypot((float)width(), (float)height())), W, H, cur))
        return false;   // the def's strength through the gaussianblur kernel
    begin(copy, blurKeep.fbo[0], W, H);
    bindTex(0, fxTex_[*cur]);
    glUniform1i(loc(copy, "uTex"), 0);
    draw();
    auto setP = [&]() {
        glUniform3f(loc(prog, "uP"), op.p[0], op.p[1], op.p[0] * 0.05f);
        glUniform2f(loc(prog, "uSize"), (float)W, (float)H);
    };
    begin(prog, hs.fbo[0], W, H);   // streak pass 1 (from the input)
    bindTex(0, inKeep.tex[0]);
    glUniform1i(loc(prog, "uTex"), 0);
    glUniform1i(loc(prog, "uPass"), 0);
    setP();
    draw();
    begin(prog, vs.fbo[0], W, H);   // streak pass 2 (from hstreak)
    bindTex(0, inKeep.tex[0]);
    bindTex(1, hs.tex[0]);
    glUniform1i(loc(prog, "uTex"), 0);
    glUniform1i(loc(prog, "uAux"), 1);
    glUniform1i(loc(prog, "uPass"), 1);
    setP();
    draw();
    const int dst = 1 - *cur;       // final cut
    begin(prog, fxFbo_[dst], W, H);
    bindTex(0, inKeep.tex[0]);
    bindTex(1, vs.tex[0]);
    bindTex(2, blurKeep.tex[0]);
    glUniform1i(loc(prog, "uTex"), 0);
    glUniform1i(loc(prog, "uAux"), 1);
    glUniform1i(loc(prog, "uBlur"), 2);
    glUniform1i(loc(prog, "uPass"), 2);
    setP();
    draw();
    *cur = dst;
    return true;
}

// Light Glow (Alight): four passes through half-size lv_ buffers (the def's downImg /
// hblur / vblur chain). op.p = [strength, threshold, intensity, colour packed, blend,
// halo, alpha].
bool Renderer::fxLightGlowAm(const FxOp& op, int W, int H, int* cur) {
    if (fxIsIdentity(op)) return true;
    const uint32_t prog = effProg(EF_LIGHT_GLOW);
    if (!prog) return false;
    const int hw = halfSize(W, 0), hh = halfSize(H, 0);
    if (!ensureLevel(0, hw, hh) || !ensureLevel(1, hw, hh)) return false;
    const FxLevel& half0 = lv_[0];
    const FxLevel& half1 = lv_[1];
    // shader uP: [strength, threshold, intensity, halo, blend, alpha, kernel]
    const float pp[7] = {op.p[0], op.p[1], op.p[2], op.p[5], op.p[4], op.p[6],
                         fxLightGlowAmKernel(op.p[0], (float)w_, (float)h_)};
    const int pk = (int)std::lround(std::min(16777215.f, std::max(0.f, op.p[3])));
    auto setCommon = [&]() {
        glUniform1fv(loc(prog, "uP"), 7, pp);
        glUniform3f(loc(prog, "uColor"), ((pk >> 16) & 0xFF) / 255.f, ((pk >> 8) & 0xFF) / 255.f,
                    (pk & 0xFF) / 255.f);
        glUniform2f(loc(prog, "uTexel"), 1.f / (float)hw, 1.f / (float)hh);
    };
    begin(prog, half0.fbo[0], hw, hh);   // pass 0: bright pick -> downImg
    bindTex(0, fxTex_[*cur]);
    glUniform1i(loc(prog, "uTex"), 0);
    glUniform1i(loc(prog, "uPass"), 0);
    setCommon();
    draw();
    begin(prog, half0.fbo[1], hw, hh);   // pass 1: horizontal blur -> hblur
    bindTex(1, half0.tex[0]);
    glUniform1i(loc(prog, "uBlur"), 1);
    glUniform1i(loc(prog, "uPass"), 1);
    setCommon();
    draw();
    begin(prog, half1.fbo[0], hw, hh);   // pass 2: vertical blur + composite -> vblur
    bindTex(0, fxTex_[*cur]);
    bindTex(1, half0.tex[1]);
    glUniform1i(loc(prog, "uTex"), 0);
    glUniform1i(loc(prog, "uBlur"), 1);
    glUniform1i(loc(prog, "uPass"), 2);
    setCommon();
    draw();
    const int dst = 1 - *cur;            // pass 3: copy out at full size
    begin(prog, fxFbo_[dst], W, H);
    bindTex(0, half1.tex[0]);
    glUniform1i(loc(prog, "uTex"), 0);
    glUniform1i(loc(prog, "uPass"), 3);
    setCommon();
    draw();
    *cur = dst;
    return true;
}

bool Renderer::fxChroma(const FxOp& op, int W, int H, int* cur) {
    const uint32_t prog = effProg(EF_CHROMA);
    if (!prog) return false;
    const ChromaPlan pl = fxPlanChroma(op);
    const int dst = 1 - *cur;
    begin(prog, fxFbo_[dst], W, H);
    bindTex(0, fxTex_[*cur]);
    glUniform1i(loc(prog, "uTex"), 0);
    glUniform2f(loc(prog, "uKey"), pl.keyCb, pl.keyCr);
    glUniform2f(loc(prog, "uDir"), pl.dirCb, pl.dirCr);
    glUniform1f(loc(prog, "uTol"), pl.tol);
    glUniform1f(loc(prog, "uSoft"), pl.soft);
    glUniform1f(loc(prog, "uSpill"), pl.spill);
    glUniform1f(loc(prog, "uKeyV"), pl.keyV);
    glUniform1f(loc(prog, "uVW"), pl.vw);
    glUniform1f(loc(prog, "uCS"), pl.cs);
    draw();
    *cur = dst;
    return true;
}

// v140: the effects that share a program. One full-screen pass: picture in `cur` -> the other target. An effect that would
// not change anything (neutral sliders) costs nothing.
bool Renderer::fxShaded(const FxOp& op, int W, int H, int* cur, int frame) {
    if (fxIsIdentity(op)) return true;
    const FxShaderRef ref = fxShaderOf(op.type);
    int id = -1;
    switch (ref.group) {
        case FXG_BLUR2: id = EF_BLUR2; break;
        case FXG_COLOR: id = EF_COLOR; break;
        case FXG_WARP: id = EF_WARP; break;
        case FXG_STYLE: id = EF_STYLE; break;
        default: return true;
    }
    const uint32_t prog = effProg(id);
    if (!prog) return false;
    const int dst = 1 - *cur;
    begin(prog, fxFbo_[dst], W, H);
    bindTex(0, fxTex_[*cur]);
    glUniform1i(loc(prog, "uTex"), 0);
    glUniform2f(loc(prog, "uTexel"), 1.f / W, 1.f / H);
    glUniform1f(loc(prog, "uAspect"), (float)W / (float)H);
    glUniform1f(loc(prog, "uUnit"), std::max(0.25f, (float)H / 1080.f));
    glUniform1i(loc(prog, "uFrame"), frame);
    glUniform1i(loc(prog, "uMode"), ref.mode);
    glUniform1fv(loc(prog, "uP"), 7, op.p);
    glUniform1fv(loc(prog, "uQ"), 7, op.q);   // only the v164 colour / warp modes read it; -1 elsewhere
    glUniform1fv(loc(prog, "uR"), 7, op.r);   // v175: only the style program's turbulence2 mode declares it
    draw();
    *cur = dst;
    return true;
}

}  // namespace am
