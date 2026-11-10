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
             EF_BLUR2, EF_COLOR, EF_WARP, EF_STYLE, EF_COPYBG, EF_COUNT };  // eff_[] in renderer.h has room for 16

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

const char* kSources[EF_COUNT] = {kDown, kBlur, kCopy, kGlowUp, kGlowComp, kSplit, kGrain, kVig, kMask, kMaskApply, kChroma,
                                  fxsrc::kBlur2, fxsrc::kColor, fxsrc::kWarp, fxsrc::kStyle, kCopyBg};
static_assert(EF_COUNT <= 16, "eff_[] in renderer.h is too small");

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
            default:
                if (op.type > FX_CHROMA && op.type <= FX_LAST) ok = fxShaded(op, W, H, cur, fx.grainFrame);
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
    const float sigma = fxBlurSigma(op.p[0], (float)H / 1080.f);
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
    draw();
    *cur = dst;
    return true;
}

}  // namespace am
