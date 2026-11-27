#include "ui_gl_renderer.h"

#include <android/log.h>

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstring>

#define TAG "AmUiGl"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace am {

namespace {

// ---- GLSL ES 3.00 Shaders (all output premultiplied RGBA for GL_ONE, GL_ONE_MINUS_SRC_ALPHA) ----

static const char* kFlatVs = R"(#version 300 es
precision highp float;
layout(location = 0) in vec2 aPos;
layout(location = 1) in vec4 aColor;
layout(location = 2) in float aCov;
uniform vec2 uViewport;
out vec4 vColor;
out float vCov;
void main() {
    vec2 ndc = vec2((aPos.x / uViewport.x) * 2.0 - 1.0, 1.0 - (aPos.y / uViewport.y) * 2.0);
    gl_Position = vec4(ndc, 0.0, 1.0);
    vColor = aColor;
    vCov = aCov;
}
)";

static const char* kFlatFs = R"(#version 300 es
precision highp float;
in vec4 vColor;
in float vCov;
out vec4 fragColor;
void main() {
    float a = vColor.a * clamp(vCov, 0.0, 1.0);
    fragColor = vec4(vColor.rgb * a, a);
}
)";

static const char* kSdfVs = R"(#version 300 es
precision highp float;
layout(location = 0) in vec2 aPos;
layout(location = 1) in vec2 aLocal;
layout(location = 2) in vec4 aParams;
layout(location = 3) in vec4 aFill;
layout(location = 4) in vec4 aStroke;
layout(location = 5) in vec4 aMeta;
uniform vec2 uViewport;
out vec2 vLocal;
out vec4 vParams;
out vec4 vFill;
out vec4 vStroke;
out vec4 vMeta;
void main() {
    vec2 ndc = vec2((aPos.x / uViewport.x) * 2.0 - 1.0, 1.0 - (aPos.y / uViewport.y) * 2.0);
    gl_Position = vec4(ndc, 0.0, 1.0);
    vLocal = aLocal;
    vParams = aParams;
    vFill = aFill;
    vStroke = aStroke;
    vMeta = aMeta;
}
)";

static const char* kSdfFs = R"(#version 300 es
precision highp float;
in vec2 vLocal;
in vec4 vParams;
in vec4 vFill;
in vec4 vStroke;
in vec4 vMeta;
out vec4 fragColor;
void main() {
    float type = vMeta.x;
    if (type < 0.5) {
        // Circle: params = (radius, strokeWidth, 0, 0)
        float r = vParams.x;
        float sw = vParams.y;
        float d = length(vLocal) - r;
        float fa = (vFill.a > 0.0001) ? smoothstep(0.6, -0.6, d) * vFill.a : 0.0;
        float sa = (sw > 0.0001 && vStroke.a > 0.0001)
            ? smoothstep(0.6, -0.6, abs(d) - sw * 0.5) * vStroke.a
            : 0.0;
        vec4 fillC = vec4(vFill.rgb * fa, fa);
        vec4 stC = vec4(vStroke.rgb * sa, sa);
        vec4 outC = stC + fillC * (1.0 - stC.a);
        if (outC.a < 0.001) discard;
        fragColor = outC;
    } else if (type < 1.5) {
        // Rounded rect: params = (halfW, halfH, cornerRadius, strokeWidth)
        vec2 hs = vParams.xy;
        float cr = min(vParams.z, min(hs.x, hs.y));
        float sw = vParams.w;
        vec2 q = abs(vLocal) - (hs - vec2(cr));
        float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - cr;
        float fa = (vFill.a > 0.0001) ? smoothstep(0.6, -0.6, d) * vFill.a : 0.0;
        float sa = (sw > 0.0001 && vStroke.a > 0.0001)
            ? smoothstep(0.6, -0.6, abs(d) - sw * 0.5) * vStroke.a
            : 0.0;
        vec4 fillC = vec4(vFill.rgb * fa, fa);
        vec4 stC = vec4(vStroke.rgb * sa, sa);
        vec4 outC = stC + fillC * (1.0 - stC.a);
        if (outC.a < 0.001) discard;
        fragColor = outC;
    } else {
        // Line segment: params = (segLen, halfWidth, dashOn, dashOff), meta = (2, dashPhase, capRound, 0)
        float len = vParams.x;
        float hw = vParams.y;
        float dashOn = vParams.z;
        float dashOff = vParams.w;
        float phase = vMeta.y;
        float capRound = vMeta.z;
        float d;
        if (capRound > 0.5) {
            float cx = clamp(vLocal.x, 0.0, len);
            d = length(vec2(vLocal.x - cx, vLocal.y)) - hw;
        } else {
            float dx = max(-vLocal.x, vLocal.x - len);
            float dy = abs(vLocal.y) - hw;
            d = max(dx, dy);
        }
        float a = smoothstep(0.6, -0.6, d);
        if (dashOn > 0.001 && dashOff > 0.001) {
            float period = dashOn + dashOff;
            float halfOn = dashOn * 0.5;
            float c = mod(vLocal.x + phase - halfOn + period * 0.5, period) - period * 0.5;
            float dashMask = smoothstep(0.6, -0.6, abs(c) - halfOn);
            a *= dashMask;
        }
        float sa = a * vStroke.a;
        if (sa < 0.001) discard;
        fragColor = vec4(vStroke.rgb * sa, sa);
    }
}
)";

// Build 249: atlas text — the atlas holds white glyphs / icon shapes with
// alpha = coverage; the per-blit tint supplies the colour. Output is
// premultiplied like every other program (GL_ONE, GL_ONE_MINUS_SRC_ALPHA).
static const char* kTexVs = R"(#version 300 es
precision highp float;
layout(location = 0) in vec2 aPos;
layout(location = 1) in vec2 aUv;
layout(location = 2) in vec4 aColor;
uniform vec2 uViewport;
out vec2 vUv;
out vec4 vColor;
void main() {
    vec2 ndc = vec2((aPos.x / uViewport.x) * 2.0 - 1.0, 1.0 - (aPos.y / uViewport.y) * 2.0);
    gl_Position = vec4(ndc, 0.0, 1.0);
    vUv = aUv;
    vColor = aColor;
}
)";

static const char* kTexFs = R"(#version 300 es
precision highp float;
uniform sampler2D uAtlas;
in vec2 vUv;
in vec4 vColor;
out vec4 fragColor;
void main() {
    float cov = texture(uAtlas, vUv).a;
    float a = vColor.a * cov;
    if (a < 0.001) discard;
    fragColor = vec4(vColor.rgb * a, a);
}
)";

static const char* kPointVs = R"(#version 300 es
precision highp float;
layout(location = 0) in vec2 aPos;
layout(location = 1) in vec4 aColor;
layout(location = 2) in float aSize;
uniform vec2 uViewport;
out vec4 vColor;
out float vQuadSize;
out float vRadius;
void main() {
    vec2 ndc = vec2((aPos.x / uViewport.x) * 2.0 - 1.0, 1.0 - (aPos.y / uViewport.y) * 2.0);
    gl_Position = vec4(ndc, 0.0, 1.0);
    float q = max(aSize + 1.2, 2.0);
    gl_PointSize = q;
    vColor = aColor;
    vQuadSize = q;
    vRadius = aSize * 0.5;
}
)";

static const char* kPointFs = R"(#version 300 es
precision highp float;
in vec4 vColor;
in float vQuadSize;
in float vRadius;
out vec4 fragColor;
void main() {
    vec2 dpx = (gl_PointCoord - vec2(0.5)) * vQuadSize;
    float d = length(dpx) - vRadius;
    float a = smoothstep(0.6, -0.6, d) * vColor.a;
    if (a < 0.001) discard;
    fragColor = vec4(vColor.rgb * a, a);
}
)";

static const char* kQuadVs = R"(#version 300 es
precision highp float;
layout(location = 0) in vec2 aPos;
layout(location = 1) in vec2 aLocal;
layout(location = 2) in vec4 aBox;
layout(location = 3) in vec4 aP03;
layout(location = 4) in vec2 aP45;
layout(location = 5) in vec4 aC0;
layout(location = 6) in vec4 aC1;
uniform vec2 uViewport;
out vec2 vLocal;
out vec4 vBox;
out vec4 vP03;
out vec2 vP45;
out vec4 vC0;
out vec4 vC1;
void main() {
    vec2 ndc = vec2((aPos.x / uViewport.x) * 2.0 - 1.0, 1.0 - (aPos.y / uViewport.y) * 2.0);
    gl_Position = vec4(ndc, 0.0, 1.0);
    vLocal = aLocal;
    vBox = aBox;
    vP03 = aP03;
    vP45 = aP45;
    vC0 = aC0;
    vC1 = aC1;
}
)";

static const char* kQuadFs = R"(#version 300 es
precision highp float;
in vec2 vLocal;
in vec4 vBox;
in vec4 vP03;
in vec2 vP45;
in vec4 vC0;
in vec4 vC1;
out vec4 fragColor;

vec3 hsvToRgb(float hDeg, float s, float v) {
    float h = mod(hDeg, 360.0);
    if (h < 0.0) h += 360.0;
    float c = v * clamp(s, 0.0, 1.0);
    float x = c * (1.0 - abs(mod(h / 60.0, 2.0) - 1.0));
    float m = v - c;
    vec3 rgb;
    if (h < 60.0) rgb = vec3(c, x, 0.0);
    else if (h < 120.0) rgb = vec3(x, c, 0.0);
    else if (h < 180.0) rgb = vec3(0.0, c, x);
    else if (h < 240.0) rgb = vec3(0.0, x, c);
    else if (h < 300.0) rgb = vec3(x, 0.0, c);
    else rgb = vec3(c, 0.0, x);
    return clamp(rgb + vec3(m), 0.0, 1.0);
}

float roundBoxMask(vec2 p, vec2 size, float r) {
    if (r <= 0.001) {
        vec2 e = min(p, size - p);
        return clamp(min(e.x, e.y) + 0.6, 0.0, 1.0);
    }
    vec2 hs = size * 0.5;
    float cr = min(r, min(hs.x, hs.y));
    vec2 q = abs(p - hs) - (hs - vec2(cr));
    float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - cr;
    return smoothstep(0.6, -0.6, d);
}

vec4 checkerSample(vec2 p, float cell, vec4 c0, vec4 c1) {
    float cs = max(2.0, cell);
    vec2 idx = floor(p / cs);
    float odd = mod(idx.x + idx.y, 2.0);
    return mix(c0, c1, odd);
}

void main() {
    vec2 size = vBox.xy;
    float cr = vBox.z;
    float mode = vBox.w;
    float clipA = roundBoxMask(vLocal, size, cr);
    if (clipA < 0.001) discard;

    if (mode < 0.5) {
        // mode 0: Checkerboard (p0 = cellPx)
        vec4 c = checkerSample(vLocal, vP03.x, vC0, vC1);
        float a = c.a * clipA;
        fragColor = vec4(c.rgb * a, a);
    } else if (mode < 1.5) {
        // mode 1: HSV Hue Wheel Ring (p0=innerR, p1=outerR, p2=selHueDeg, p3=showReticle)
        vec2 c = size * 0.5;
        vec2 dp = vLocal - c;
        float dist = length(dp);
        float innerR = vP03.x;
        float outerR = vP03.y;
        float ringA = smoothstep(innerR - 0.8, innerR + 0.8, dist) *
                      smoothstep(outerR + 0.8, outerR - 0.8, dist);
        float angDeg = degrees(atan(dp.y, dp.x));
        if (angDeg < 0.0) angDeg += 360.0;
        vec3 col = hsvToRgb(angDeg, 1.0, 1.0);
        vec4 outC = vec4(col * ringA, ringA);
        if (vP03.w > 0.5) {
            float rad = radians(vP03.z);
            float midR = (innerR + outerR) * 0.5;
            vec2 rp = c + vec2(cos(rad), sin(rad)) * midR;
            float rd = length(vLocal - rp);
            float retR = max(3.5, (outerR - innerR) * 0.36);
            float rimA = smoothstep(1.2, 0.0, abs(rd - retR) - 1.5);
            float darkA = smoothstep(1.2, 0.0, abs(rd - retR) - 2.6) * 0.45;
            vec4 dCol = vec4(0.0, 0.0, 0.0, darkA);
            vec4 wCol = vec4(vec3(rimA), rimA);
            outC = dCol + outC * (1.0 - dCol.a);
            outC = wCol + outC * (1.0 - wCol.a);
        }
        if (outC.a < 0.001) discard;
        fragColor = outC;
    } else if (mode < 2.5) {
        // mode 2: HSV SV Box (p0=hueDeg, p1=selSat, p2=selVal, p3=showReticle)
        float s = clamp(vLocal.x / max(1.0, size.x), 0.0, 1.0);
        float v = 1.0 - clamp(vLocal.y / max(1.0, size.y), 0.0, 1.0);
        vec3 col = hsvToRgb(vP03.x, s, v);
        vec4 outC = vec4(col * clipA, clipA);
        if (vP03.w > 0.5) {
            vec2 rp = vec2(clamp(vP03.y, 0.0, 1.0) * size.x, (1.0 - clamp(vP03.z, 0.0, 1.0)) * size.y);
            float rd = length(vLocal - rp);
            float retR = max(4.0, vP45.x);
            float darkRim = smoothstep(1.0, 0.0, abs(rd - retR) - 2.1) * 0.55;
            float whiteRim = smoothstep(1.0, 0.0, abs(rd - retR) - 1.1);
            vec4 dCol = vec4(0.0, 0.0, 0.0, darkRim);
            vec4 wCol = vec4(vec3(whiteRim), whiteRim);
            outC = dCol + outC * (1.0 - dCol.a);
            outC = wCol + outC * (1.0 - wCol.a);
        }
        fragColor = outC;
    } else if (mode < 3.5) {
        // mode 3: Horizontal Bar (p0=subMode: 0=2-stop, 1=rainbow, 2=checker+alpha, 3=sat, 4=val)
        float sub = vP03.x;
        float frac = clamp(vLocal.x / max(1.0, size.x), 0.0, 1.0);
        vec4 c;
        if (sub < 0.5) {
            c = mix(vC0, vC1, frac);
        } else if (sub < 1.5) {
            c = vec4(hsvToRgb(frac * 360.0, 1.0, 1.0), 1.0);
        } else if (sub < 2.5) {
            vec4 chk = checkerSample(vLocal, 7.0, vec4(0.32, 0.32, 0.34, 1.0), vec4(0.18, 0.18, 0.20, 1.0));
            vec4 fg = mix(vec4(vC0.rgb, 0.0), vec4(vC1.rgb, 1.0), frac);
            c = vec4(mix(chk.rgb, fg.rgb, fg.a), 1.0);
        } else if (sub < 3.5) {
            c = vec4(hsvToRgb(vP03.y, frac, vP03.z), 1.0);
        } else {
            c = vec4(hsvToRgb(vP03.y, vP03.z, frac), 1.0);
        }
        float a = c.a * clipA;
        vec4 outC = vec4(c.rgb * a, a);
        float thumbFrac = vP45.x;
        if (thumbFrac >= 0.0) {
            float tx = clamp(thumbFrac, 0.0, 1.0) * size.x;
            float ty = size.y * 0.5;
            float r = max(3.5, size.y * 0.38);
            float d = length(vLocal - vec2(tx, ty)) - r;
            float fillA = smoothstep(0.8, -0.8, d);
            float borderA = smoothstep(0.8, -0.8, abs(d) - 1.5) * 0.65;
            vec4 fillCol = vec4(vec3(fillA), fillA);
            vec4 bdCol = vec4(0.0, 0.0, 0.0, borderA);
            outC = fillCol + outC * (1.0 - fillCol.a);
            outC = bdCol + outC * (1.0 - bdCol.a);
        }
        fragColor = outC;
    } else {
        // mode 4: Gradient Type Tile (p0=0:linear, 1:radial, 2:sweep)
        float gt = vP03.x;
        vec2 uv = vLocal / max(vec2(1.0), size);
        float t;
        if (gt < 0.5) {
            t = clamp((uv.x + uv.y) * 0.5, 0.0, 1.0);
        } else if (gt < 1.5) {
            t = clamp(length(uv - vec2(0.5)) * 1.45, 0.0, 1.0);
        } else {
            float ang = atan(uv.y - 0.5, uv.x - 0.5) + 3.14159265;
            t = abs(mod(ang / 3.14159265, 2.0) - 1.0);
        }
        vec4 c = mix(vC0, vC1, t);
        float a = c.a * clipA;
        fragColor = vec4(c.rgb * a, a);
    }
}
)";

static GLuint compileShader(GLenum type, const char* src) {
    GLuint sh = glCreateShader(type);
    if (!sh) return 0;
    glShaderSource(sh, 1, &src, nullptr);
    glCompileShader(sh);
    GLint ok = 0;
    glGetShaderiv(sh, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[512] = {0};
        glGetShaderInfoLog(sh, sizeof(log) - 1, nullptr, log);
        LOGE("Shader compile failed (%u): %s", type, log);
        glDeleteShader(sh);
        return 0;
    }
    return sh;
}

static GLuint linkProgram(const char* vsSrc, const char* fsSrc) {
    GLuint vs = compileShader(GL_VERTEX_SHADER, vsSrc);
    if (!vs) return 0;
    GLuint fs = compileShader(GL_FRAGMENT_SHADER, fsSrc);
    if (!fs) {
        glDeleteShader(vs);
        return 0;
    }
    GLuint prog = glCreateProgram();
    glAttachShader(prog, vs);
    glAttachShader(prog, fs);
    glLinkProgram(prog);
    glDeleteShader(vs);
    glDeleteShader(fs);
    GLint ok = 0;
    glGetProgramiv(prog, GL_LINK_STATUS, &ok);
    if (!ok) {
        char log[512] = {0};
        glGetProgramInfoLog(prog, sizeof(log) - 1, nullptr, log);
        LOGE("Program link failed: %s", log);
        glDeleteProgram(prog);
        return 0;
    }
    return prog;
}

// Scope colours — exact match to ScopesPanel.kt
constexpr int32_t kPlotBg      = static_cast<int32_t>(0xFF0D0D0D);
constexpr int32_t kGridLine    = static_cast<int32_t>(0xFF262626);
constexpr int32_t kHistRed     = static_cast<int32_t>(0x80FF4444);
constexpr int32_t kHistGreen   = static_cast<int32_t>(0x8051FF51);
constexpr int32_t kHistBlue    = static_cast<int32_t>(0x805599FF);
constexpr int32_t kHistLuma    = static_cast<int32_t>(0xF2FFFFFF);
constexpr int32_t kWaveFaint   = static_cast<int32_t>(0x4DB7FFB7);  // alpha 0.30
constexpr int32_t kWaveBright  = static_cast<int32_t>(0xF2B7FFB7);  // alpha 0.95
constexpr int32_t kVecFaint    = static_cast<int32_t>(0x4DA8FFF2);  // alpha 0.30
constexpr int32_t kVecBright   = static_cast<int32_t>(0xF2A8FFF2);  // alpha 0.95
constexpr int32_t kVecTarget   = static_cast<int32_t>(0xFF4A4A4A);

// EasingEditor colours — exact match to EasingEditor.kt
constexpr int32_t kEaseGrid    = static_cast<int32_t>(0xFF4A4C50);
constexpr int32_t kEasePlay    = static_cast<int32_t>(0xFFC9CBCF);
constexpr int32_t kEaseArm     = static_cast<int32_t>(0xFF6B6E73);
constexpr int32_t kEaseWhite   = static_cast<int32_t>(0xFFFFFFFF);
constexpr int32_t kEaseHandle  = static_cast<int32_t>(0xFFEA1252);

constexpr float kVecFullScale = 0.65f;
constexpr float kEaseYMin = -0.3f;
constexpr float kEaseYMax = 1.3f;

inline float bt601Cb(int r, int g, int b) {
    return (-0.14713f * r - 0.28886f * g + 0.436f * b) / 255.0f;
}

inline float bt601Cr(int r, int g, int b) {
    return (0.615f * r - 0.51499f * g - 0.10001f * b) / 255.0f;
}

}  // namespace

UiGlRenderer::~UiGlRenderer() {
    release();
}

UiGlRenderer::Rgba UiGlRenderer::argbToRgba(int32_t argb) {
    const uint32_t u = static_cast<uint32_t>(argb);
    return {
        ((u >> 16) & 0xFFu) / 255.0f,
        ((u >> 8) & 0xFFu) / 255.0f,
        (u & 0xFFu) / 255.0f,
        ((u >> 24) & 0xFFu) / 255.0f
    };
}

bool UiGlRenderer::init() {
    if (initialized_) return true;
    if (!egl_.init()) {
        LOGE("EglCore init failed");
        return false;
    }
    currentSurf_ = egl_.bootstrapSurface();
    if (!compileShaders()) {
        release();
        return false;
    }

    // Flat VAO/VBO
    glGenVertexArrays(1, &vaoFlat_);
    glGenBuffers(1, &vboFlat_);
    glBindVertexArray(vaoFlat_);
    glBindBuffer(GL_ARRAY_BUFFER, vboFlat_);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, sizeof(FlatVertex),
                          reinterpret_cast<const void*>(offsetof(FlatVertex, x)));
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 4, GL_FLOAT, GL_FALSE, sizeof(FlatVertex),
                          reinterpret_cast<const void*>(offsetof(FlatVertex, r)));
    glEnableVertexAttribArray(2);
    glVertexAttribPointer(2, 1, GL_FLOAT, GL_FALSE, sizeof(FlatVertex),
                          reinterpret_cast<const void*>(offsetof(FlatVertex, cov)));

    // SDF VAO/VBO
    glGenVertexArrays(1, &vaoSdf_);
    glGenBuffers(1, &vboSdf_);
    glBindVertexArray(vaoSdf_);
    glBindBuffer(GL_ARRAY_BUFFER, vboSdf_);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, sizeof(SdfVertex),
                          reinterpret_cast<const void*>(offsetof(SdfVertex, x)));
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, sizeof(SdfVertex),
                          reinterpret_cast<const void*>(offsetof(SdfVertex, lx)));
    glEnableVertexAttribArray(2);
    glVertexAttribPointer(2, 4, GL_FLOAT, GL_FALSE, sizeof(SdfVertex),
                          reinterpret_cast<const void*>(offsetof(SdfVertex, p0)));
    glEnableVertexAttribArray(3);
    glVertexAttribPointer(3, 4, GL_FLOAT, GL_FALSE, sizeof(SdfVertex),
                          reinterpret_cast<const void*>(offsetof(SdfVertex, fr)));
    glEnableVertexAttribArray(4);
    glVertexAttribPointer(4, 4, GL_FLOAT, GL_FALSE, sizeof(SdfVertex),
                          reinterpret_cast<const void*>(offsetof(SdfVertex, sr)));
    glEnableVertexAttribArray(5);
    glVertexAttribPointer(5, 4, GL_FLOAT, GL_FALSE, sizeof(SdfVertex),
                          reinterpret_cast<const void*>(offsetof(SdfVertex, type)));

    // Point VAO/VBO
    glGenVertexArrays(1, &vaoPoint_);
    glGenBuffers(1, &vboPoint_);
    glBindVertexArray(vaoPoint_);
    glBindBuffer(GL_ARRAY_BUFFER, vboPoint_);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, sizeof(PointVertex),
                          reinterpret_cast<const void*>(offsetof(PointVertex, x)));
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 4, GL_FLOAT, GL_FALSE, sizeof(PointVertex),
                          reinterpret_cast<const void*>(offsetof(PointVertex, r)));
    glEnableVertexAttribArray(2);
    glVertexAttribPointer(2, 1, GL_FLOAT, GL_FALSE, sizeof(PointVertex),
                          reinterpret_cast<const void*>(offsetof(PointVertex, size)));

    // Quad VAO/VBO
    glGenVertexArrays(1, &vaoQuad_);
    glGenBuffers(1, &vboQuad_);
    glBindVertexArray(vaoQuad_);
    glBindBuffer(GL_ARRAY_BUFFER, vboQuad_);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, sizeof(QuadVertex),
                          reinterpret_cast<const void*>(offsetof(QuadVertex, x)));
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, sizeof(QuadVertex),
                          reinterpret_cast<const void*>(offsetof(QuadVertex, lx)));
    glEnableVertexAttribArray(2);
    glVertexAttribPointer(2, 4, GL_FLOAT, GL_FALSE, sizeof(QuadVertex),
                          reinterpret_cast<const void*>(offsetof(QuadVertex, w)));
    glEnableVertexAttribArray(3);
    glVertexAttribPointer(3, 4, GL_FLOAT, GL_FALSE, sizeof(QuadVertex),
                          reinterpret_cast<const void*>(offsetof(QuadVertex, p0)));
    glEnableVertexAttribArray(4);
    glVertexAttribPointer(4, 2, GL_FLOAT, GL_FALSE, sizeof(QuadVertex),
                          reinterpret_cast<const void*>(offsetof(QuadVertex, p4)));
    glEnableVertexAttribArray(5);
    glVertexAttribPointer(5, 4, GL_FLOAT, GL_FALSE, sizeof(QuadVertex),
                          reinterpret_cast<const void*>(offsetof(QuadVertex, c0r)));
    glEnableVertexAttribArray(6);
    glVertexAttribPointer(6, 4, GL_FLOAT, GL_FALSE, sizeof(QuadVertex),
                          reinterpret_cast<const void*>(offsetof(QuadVertex, c1r)));

    // Tex (atlas blit) VAO/VBO — build 249
    glGenVertexArrays(1, &vaoTex_);
    glGenBuffers(1, &vboTex_);
    glBindVertexArray(vaoTex_);
    glBindBuffer(GL_ARRAY_BUFFER, vboTex_);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, sizeof(TexVertex),
                          reinterpret_cast<const void*>(offsetof(TexVertex, x)));
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, sizeof(TexVertex),
                          reinterpret_cast<const void*>(offsetof(TexVertex, u)));
    glEnableVertexAttribArray(2);
    glVertexAttribPointer(2, 4, GL_FLOAT, GL_FALSE, sizeof(TexVertex),
                          reinterpret_cast<const void*>(offsetof(TexVertex, r)));

    glBindVertexArray(0);
    glBindBuffer(GL_ARRAY_BUFFER, 0);

    initialized_ = true;
    LOGI("UiGlRenderer initialized");
    return true;
}

bool UiGlRenderer::compileShaders() {
    progFlat_ = linkProgram(kFlatVs, kFlatFs);
    progSdf_ = linkProgram(kSdfVs, kSdfFs);
    progPoint_ = linkProgram(kPointVs, kPointFs);
    progQuad_ = linkProgram(kQuadVs, kQuadFs);
    progTex_ = linkProgram(kTexVs, kTexFs);
    if (!progFlat_ || !progSdf_ || !progPoint_ || !progQuad_ || !progTex_) return false;
    uFlatViewport_ = glGetUniformLocation(progFlat_, "uViewport");
    uSdfViewport_ = glGetUniformLocation(progSdf_, "uViewport");
    uPointViewport_ = glGetUniformLocation(progPoint_, "uViewport");
    uQuadViewport_ = glGetUniformLocation(progQuad_, "uViewport");
    uTexViewport_ = glGetUniformLocation(progTex_, "uViewport");
    uTexAtlas_ = glGetUniformLocation(progTex_, "uAtlas");
    return true;
}

void UiGlRenderer::release() {
    if (!initialized_ && surfaces_.empty()) {
        egl_.release();
        return;
    }
    if (initialized_) {
        egl_.makeCurrent(egl_.bootstrapSurface());
        currentSurf_ = egl_.bootstrapSurface();
        for (auto& kv : surfaces_) {
            if (kv.second.surface != EGL_NO_SURFACE) egl_.destroySurface(kv.second.surface);
            if (kv.second.window) ANativeWindow_release(kv.second.window);
        }
        surfaces_.clear();
        if (vboFlat_) glDeleteBuffers(1, &vboFlat_);
        if (vaoFlat_) glDeleteVertexArrays(1, &vaoFlat_);
        if (vboSdf_) glDeleteBuffers(1, &vboSdf_);
        if (vaoSdf_) glDeleteVertexArrays(1, &vaoSdf_);
        if (vboPoint_) glDeleteBuffers(1, &vboPoint_);
        if (vaoPoint_) glDeleteVertexArrays(1, &vaoPoint_);
        if (vboQuad_) glDeleteBuffers(1, &vboQuad_);
        if (vaoQuad_) glDeleteVertexArrays(1, &vaoQuad_);
        if (vboTex_) glDeleteBuffers(1, &vboTex_);
        if (vaoTex_) glDeleteVertexArrays(1, &vaoTex_);
        if (atlasTex_) glDeleteTextures(1, &atlasTex_);
        if (progFlat_) glDeleteProgram(progFlat_);
        if (progSdf_) glDeleteProgram(progSdf_);
        if (progPoint_) glDeleteProgram(progPoint_);
        if (progQuad_) glDeleteProgram(progQuad_);
        if (progTex_) glDeleteProgram(progTex_);
        vboFlat_ = vaoFlat_ = vboSdf_ = vaoSdf_ = vboPoint_ = vaoPoint_ = vboQuad_ = vaoQuad_ = 0;
        vboTex_ = vaoTex_ = atlasTex_ = 0;
        progFlat_ = progSdf_ = progPoint_ = progQuad_ = progTex_ = 0;
        atlasW_ = atlasH_ = 0;
        atlasEntries_.clear();
        initialized_ = false;
    }
    egl_.release();
    currentSurf_ = EGL_NO_SURFACE;
}

int UiGlRenderer::attachSurface(ANativeWindow* window, int w, int h) {
    if (!initialized_ || !window) {
        if (window) ANativeWindow_release(window);
        return 0;
    }
    EGLSurface surf = egl_.createWindowSurface(window);
    if (surf == EGL_NO_SURFACE) {
        ANativeWindow_release(window);
        return 0;
    }
    int sw = egl_.width(surf);
    int sh = egl_.height(surf);
    if (sw <= 0) sw = w;
    if (sh <= 0) sh = h;
    int id = nextSurfaceId_++;
    surfaces_[id] = SurfaceSlot{window, surf, sw, sh};
    return id;
}

void UiGlRenderer::resizeSurface(int surfaceId, int w, int h) {
    auto it = surfaces_.find(surfaceId);
    if (it == surfaces_.end()) return;
    if (w > 0) it->second.w = w;
    if (h > 0) it->second.h = h;
}

void UiGlRenderer::detachSurface(int surfaceId) {
    auto it = surfaces_.find(surfaceId);
    if (it == surfaces_.end()) return;
    if (currentSurf_ == it->second.surface) {
        egl_.makeCurrent(egl_.bootstrapSurface());
        currentSurf_ = egl_.bootstrapSurface();
    }
    if (it->second.surface != EGL_NO_SURFACE) egl_.destroySurface(it->second.surface);
    if (it->second.window) ANativeWindow_release(it->second.window);
    surfaces_.erase(it);
}

bool UiGlRenderer::beginSurface(int surfaceId, int& outW, int& outH, EGLSurface& outSurf) {
    if (!initialized_) return false;
    auto it = surfaces_.find(surfaceId);
    if (it == surfaces_.end()) return false;
    EGLSurface surf = it->second.surface;
    if (surf == EGL_NO_SURFACE) return false;
    if (currentSurf_ != surf) {
        if (!egl_.makeCurrent(surf)) {
            currentSurf_ = EGL_NO_SURFACE;
            return false;
        }
        currentSurf_ = surf;
    }
    int qw = egl_.width(surf);
    int qh = egl_.height(surf);
    outW = qw > 0 ? qw : it->second.w;
    outH = qh > 0 ? qh : it->second.h;
    if (outW <= 0 || outH <= 0) return false;
    outSurf = surf;
    glViewport(0, 0, outW, outH);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_CULL_FACE);
    glEnable(GL_BLEND);
    glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    return true;
}

void UiGlRenderer::clearColor(int32_t argb) {
    Rgba c = argbToRgba(argb);
    glClearColor(c.r * c.a, c.g * c.a, c.b * c.a, c.a);
    glClear(GL_COLOR_BUFFER_BIT);
}

void UiGlRenderer::resetBatches() {
    flatVerts_.clear();
    sdfVerts_.clear();
    pointVerts_.clear();
    quadVerts_.clear();
    texVerts_.clear();
}

void UiGlRenderer::flushBatches(int w, int h) {
    const float fw = static_cast<float>(w);
    const float fh = static_cast<float>(h);

    // Draw procedural shader quads first so vector overlays/reticles composite cleanly on top.
    if (!quadVerts_.empty()) {
        glUseProgram(progQuad_);
        glUniform2f(uQuadViewport_, fw, fh);
        glBindVertexArray(vaoQuad_);
        glBindBuffer(GL_ARRAY_BUFFER, vboQuad_);
        glBufferData(
            GL_ARRAY_BUFFER,
            static_cast<GLsizeiptr>(quadVerts_.size() * sizeof(QuadVertex)),
            quadVerts_.data(),
            GL_STREAM_DRAW);
        glDrawArrays(GL_TRIANGLES, 0, static_cast<GLsizei>(quadVerts_.size()));
    }

    if (!flatVerts_.empty()) {
        glUseProgram(progFlat_);
        glUniform2f(uFlatViewport_, fw, fh);
        glBindVertexArray(vaoFlat_);
        glBindBuffer(GL_ARRAY_BUFFER, vboFlat_);
        glBufferData(
            GL_ARRAY_BUFFER,
            static_cast<GLsizeiptr>(flatVerts_.size() * sizeof(FlatVertex)),
            flatVerts_.data(),
            GL_STREAM_DRAW);
        glDrawArrays(GL_TRIANGLES, 0, static_cast<GLsizei>(flatVerts_.size()));
    }

    if (!sdfVerts_.empty()) {
        glUseProgram(progSdf_);
        glUniform2f(uSdfViewport_, fw, fh);
        glBindVertexArray(vaoSdf_);
        glBindBuffer(GL_ARRAY_BUFFER, vboSdf_);
        glBufferData(
            GL_ARRAY_BUFFER,
            static_cast<GLsizeiptr>(sdfVerts_.size() * sizeof(SdfVertex)),
            sdfVerts_.data(),
            GL_STREAM_DRAW);
        glDrawArrays(GL_TRIANGLES, 0, static_cast<GLsizei>(sdfVerts_.size()));
    }

    if (!pointVerts_.empty()) {
        glUseProgram(progPoint_);
        glUniform2f(uPointViewport_, fw, fh);
        glBindVertexArray(vaoPoint_);
        glBindBuffer(GL_ARRAY_BUFFER, vboPoint_);
        glBufferData(
            GL_ARRAY_BUFFER,
            static_cast<GLsizeiptr>(pointVerts_.size() * sizeof(PointVertex)),
            pointVerts_.data(),
            GL_STREAM_DRAW);
        glDrawArrays(GL_POINTS, 0, static_cast<GLsizei>(pointVerts_.size()));
    }

    // Atlas blits flush LAST: labels and icons always composite on top of the
    // surface's own vector art (tile backgrounds, glyphs, the ruler needle).
    if (!texVerts_.empty() && atlasTex_ != 0) {
        glUseProgram(progTex_);
        glUniform2f(uTexViewport_, fw, fh);
        glUniform1i(uTexAtlas_, 0);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, atlasTex_);
        glBindVertexArray(vaoTex_);
        glBindBuffer(GL_ARRAY_BUFFER, vboTex_);
        glBufferData(
            GL_ARRAY_BUFFER,
            static_cast<GLsizeiptr>(texVerts_.size() * sizeof(TexVertex)),
            texVerts_.data(),
            GL_STREAM_DRAW);
        glDrawArrays(GL_TRIANGLES, 0, static_cast<GLsizei>(texVerts_.size()));
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    glBindVertexArray(0);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
}

void UiGlRenderer::setAtlas(const uint8_t* rgba, int w, int h, const int32_t* rects, int entryCount) {
    if (!initialized_) return;
    atlasEntries_.clear();
    if (rects && entryCount > 0) {
        // Same hardening as the timeline atlas (build 227): cap the count and
        // clamp every entry into the bitmap's bounds, so every blit is
        // in-bounds by construction.
        const int n = entryCount > (1 << 20) ? (1 << 20) : entryCount;
        atlasEntries_.resize(static_cast<size_t>(n));
        for (int i = 0; i < n; ++i) {
            const int32_t ex = std::clamp<int32_t>(rects[i * 4], 0, w);
            const int32_t ey = std::clamp<int32_t>(rects[i * 4 + 1], 0, h);
            atlasEntries_[static_cast<size_t>(i)].x = ex;
            atlasEntries_[static_cast<size_t>(i)].y = ey;
            atlasEntries_[static_cast<size_t>(i)].w = std::clamp<int32_t>(rects[i * 4 + 2], 0, w - ex);
            atlasEntries_[static_cast<size_t>(i)].h = std::clamp<int32_t>(rects[i * 4 + 3], 0, h - ey);
        }
    }
    if (!rgba || w <= 0 || h <= 0) {
        atlasW_ = 0;
        atlasH_ = 0;
        return;
    }
    // The texture lives in the renderer's one EGL context; make sure that
    // context is current (between draws the bootstrap surface may be bound —
    // beginSurface re-binds the target surface on the next draw regardless).
    egl_.makeCurrent(egl_.bootstrapSurface());
    currentSurf_ = egl_.bootstrapSurface();
    if (atlasTex_ == 0) glGenTextures(1, &atlasTex_);
    glBindTexture(GL_TEXTURE_2D, atlasTex_);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, rgba);
    glBindTexture(GL_TEXTURE_2D, 0);
    atlasW_ = w;
    atlasH_ = h;
}

void UiGlRenderer::addTextBlit(
    int entry, float dstX, float dstY,
    float srcDX, float srcDY, float srcW, float srcH,
    float dstW, float dstH, Rgba tint) {
    if (atlasTex_ == 0 || atlasW_ <= 0 || atlasH_ <= 0) return;
    if (entry < 0 || entry >= static_cast<int>(atlasEntries_.size())) return;
    const AtlasEntry& e = atlasEntries_[static_cast<size_t>(entry)];
    if (e.w <= 0 || e.h <= 0 || srcW <= 0.0f || srcH <= 0.0f) return;
    // Clamp the src sub-rect into the entry (callers pass entry-relative px).
    const float sx = std::clamp(srcDX, 0.0f, static_cast<float>(e.w));
    const float sy = std::clamp(srcDY, 0.0f, static_cast<float>(e.h));
    const float sw = std::clamp(srcW, 0.0f, static_cast<float>(e.w) - sx);
    const float sh = std::clamp(srcH, 0.0f, static_cast<float>(e.h) - sy);
    if (sw <= 0.0f || sh <= 0.0f) return;
    const float dw = dstW > 0.0f ? dstW : sw;
    const float dh = dstH > 0.0f ? dstH : sh;
    const float invW = 1.0f / static_cast<float>(atlasW_);
    const float invH = 1.0f / static_cast<float>(atlasH_);
    const float u0 = (static_cast<float>(e.x) + sx) * invW;
    const float v0 = (static_cast<float>(e.y) + sy) * invH;
    const float u1 = (static_cast<float>(e.x) + sx + sw) * invW;
    const float v1 = (static_cast<float>(e.y) + sy + sh) * invH;
    const float x0 = dstX;
    const float y0 = dstY;
    const float x1 = dstX + dw;
    const float y1 = dstY + dh;
    texVerts_.push_back({x0, y0, u0, v0, tint.r, tint.g, tint.b, tint.a});
    texVerts_.push_back({x1, y0, u1, v0, tint.r, tint.g, tint.b, tint.a});
    texVerts_.push_back({x1, y1, u1, v1, tint.r, tint.g, tint.b, tint.a});
    texVerts_.push_back({x0, y0, u0, v0, tint.r, tint.g, tint.b, tint.a});
    texVerts_.push_back({x1, y1, u1, v1, tint.r, tint.g, tint.b, tint.a});
    texVerts_.push_back({x0, y1, u0, v1, tint.r, tint.g, tint.b, tint.a});
}

// ---- Geometry batch builders ----

void UiGlRenderer::addRect(float x, float y, float w, float h, Rgba fill) {
    if (fill.a <= 0.0f || w <= 0.0f || h <= 0.0f) return;
    const float x1 = x + w;
    const float y1 = y + h;
    flatVerts_.push_back({x,  y,  fill.r, fill.g, fill.b, fill.a, 1.0f});
    flatVerts_.push_back({x1, y,  fill.r, fill.g, fill.b, fill.a, 1.0f});
    flatVerts_.push_back({x1, y1, fill.r, fill.g, fill.b, fill.a, 1.0f});
    flatVerts_.push_back({x,  y,  fill.r, fill.g, fill.b, fill.a, 1.0f});
    flatVerts_.push_back({x1, y1, fill.r, fill.g, fill.b, fill.a, 1.0f});
    flatVerts_.push_back({x,  y1, fill.r, fill.g, fill.b, fill.a, 1.0f});
}

void UiGlRenderer::addTri(float x0, float y0, float x1, float y1, float x2, float y2, Rgba c) {
    flatVerts_.push_back({x0, y0, c.r, c.g, c.b, c.a, 1.0f});
    flatVerts_.push_back({x1, y1, c.r, c.g, c.b, c.a, 1.0f});
    flatVerts_.push_back({x2, y2, c.r, c.g, c.b, c.a, 1.0f});
}

void UiGlRenderer::addCircle(float cx, float cy, float radius, float strokeWidth, Rgba fill, Rgba stroke) {
    if (radius <= 0.0f && strokeWidth <= 0.0f) return;
    if (fill.a <= 0.0f && (strokeWidth <= 0.0f || stroke.a <= 0.0f)) return;
    const float ext = radius + strokeWidth * 0.5f + 1.5f;
    auto push = [&](float dx, float dy) {
        sdfVerts_.push_back({
            cx + dx, cy + dy, dx, dy,
            radius, strokeWidth, 0.0f, 0.0f,
            fill.r, fill.g, fill.b, fill.a,
            stroke.r, stroke.g, stroke.b, stroke.a,
            0.0f, 0.0f, 0.0f, 0.0f
        });
    };
    push(-ext, -ext); push( ext, -ext); push( ext,  ext);
    push(-ext, -ext); push( ext,  ext); push(-ext,  ext);
}

void UiGlRenderer::addRoundRect(
    float cx, float cy, float w, float h, float cornerR, float strokeWidth, Rgba fill, Rgba stroke) {
    if (w <= 0.0f || h <= 0.0f) return;
    if (fill.a <= 0.0f && (strokeWidth <= 0.0f || stroke.a <= 0.0f)) return;
    const float hw = w * 0.5f;
    const float hh = h * 0.5f;
    const float pad = strokeWidth * 0.5f + 1.5f;
    const float ex = hw + pad;
    const float ey = hh + pad;
    auto push = [&](float dx, float dy) {
        sdfVerts_.push_back({
            cx + dx, cy + dy, dx, dy,
            hw, hh, cornerR, strokeWidth,
            fill.r, fill.g, fill.b, fill.a,
            stroke.r, stroke.g, stroke.b, stroke.a,
            1.0f, 0.0f, 0.0f, 0.0f
        });
    };
    push(-ex, -ey); push( ex, -ey); push( ex,  ey);
    push(-ex, -ey); push( ex,  ey); push(-ex,  ey);
}

void UiGlRenderer::addSegmentSdf(
    float x0, float y0, float x1, float y1,
    float width, float dashOn, float dashOff, float dashPhase, bool capRound, Rgba stroke) {
    if (width <= 0.0f || stroke.a <= 0.0f) return;
    const float dx = x1 - x0;
    const float dy = y1 - y0;
    const float len = std::hypot(dx, dy);
    const float hw = width * 0.5f;
    if (len < 1e-4f) {
        if (capRound) addCircle(x0, y0, hw, 0.0f, stroke, {0, 0, 0, 0});
        return;
    }
    const float ux = dx / len;
    const float uy = dy / len;
    const float nx = -uy;
    const float ny = ux;
    const float padX = (capRound ? hw : 0.0f) + 1.5f;
    const float padY = hw + 1.5f;
    const float capFlag = capRound ? 1.0f : 0.0f;

    auto push = [&](float s, float t) {
        sdfVerts_.push_back({
            x0 + ux * s + nx * t,
            y0 + uy * s + ny * t,
            s, t,
            len, hw, dashOn, dashOff,
            0.0f, 0.0f, 0.0f, 0.0f,
            stroke.r, stroke.g, stroke.b, stroke.a,
            2.0f, dashPhase, capFlag, 0.0f
        });
    };
    push(-padX,       -padY);
    push(len + padX,  -padY);
    push(len + padX,   padY);
    push(-padX,       -padY);
    push(len + padX,   padY);
    push(-padX,        padY);
}

void UiGlRenderer::addPolylineRibbon(
    const float* xy, int count, bool closed, float width, Rgba stroke) {
    if (count < 2 || width <= 0.0f || stroke.a <= 0.0f) return;

    // Deduplicate near-identical consecutive points so tangents stay finite.
    std::vector<float> px;
    std::vector<float> py;
    px.reserve(static_cast<size_t>(count));
    py.reserve(static_cast<size_t>(count));
    for (int i = 0; i < count; ++i) {
        const float x = xy[i * 2];
        const float y = xy[i * 2 + 1];
        if (px.empty() || std::hypot(x - px.back(), y - py.back()) > 1e-3f) {
            px.push_back(x);
            py.push_back(y);
        }
    }
    if (closed && px.size() > 2 && std::hypot(px.front() - px.back(), py.front() - py.back()) <= 1e-3f) {
        px.pop_back();
        py.pop_back();
    }
    const int n = static_cast<int>(px.size());
    if (n < 2) return;
    if (n == 2 && !closed) {
        addSegmentSdf(px[0], py[0], px[1], py[1], width, 0.0f, 0.0f, 0.0f, true, stroke);
        return;
    }

    const float hw = width * 0.5f;
    const float core = std::max(0.0f, hw - 0.5f);
    const float outer = hw + 0.6f;
    const float coreCov = std::min(1.0f, width);

    struct Ring {
        float lox, loy, lix, liy, rix, riy, rox, roy;
    };
    std::vector<Ring> rings(static_cast<size_t>(n));

    auto dirBetween = [&](int a, int b, float& ux, float& uy) -> bool {
        float dx = px[static_cast<size_t>(b)] - px[static_cast<size_t>(a)];
        float dy = py[static_cast<size_t>(b)] - py[static_cast<size_t>(a)];
        float d = std::hypot(dx, dy);
        if (d < 1e-5f) {
            ux = 1.0f;
            uy = 0.0f;
            return false;
        }
        ux = dx / d;
        uy = dy / d;
        return true;
    };

    for (int i = 0; i < n; ++i) {
        float u0x = 0.0f, u0y = 0.0f, u1x = 0.0f, u1y = 0.0f;
        bool hasIn = false;
        bool hasOut = false;
        if (closed) {
            hasIn = dirBetween((i + n - 1) % n, i, u0x, u0y);
            hasOut = dirBetween(i, (i + 1) % n, u1x, u1y);
        } else {
            if (i > 0) hasIn = dirBetween(i - 1, i, u0x, u0y);
            if (i < n - 1) hasOut = dirBetween(i, i + 1, u1x, u1y);
        }
        float mx = 0.0f, my = 1.0f, scale = 1.0f;
        if (hasIn && hasOut) {
            const float n0x = -u0y, n0y = u0x;
            const float n1x = -u1y, n1y = u1x;
            const float bx = n0x + n1x;
            const float by = n0y + n1y;
            const float bl = std::hypot(bx, by);
            if (bl > 1e-4f) {
                mx = bx / bl;
                my = by / bl;
                const float denom = std::max(mx * n1x + my * n1y, 0.4f);
                scale = 1.0f / denom;
            } else {
                mx = n1x;
                my = n1y;
                scale = 1.0f;
            }
        } else if (hasOut) {
            mx = -u1y;
            my = u1x;
        } else if (hasIn) {
            mx = -u0y;
            my = u0x;
        }
        const float cx = px[static_cast<size_t>(i)];
        const float cy = py[static_cast<size_t>(i)];
        const float cs = core * scale;
        const float os = outer * scale;
        rings[static_cast<size_t>(i)] = {
            cx + mx * os, cy + my * os,
            cx + mx * cs, cy + my * cs,
            cx - mx * cs, cy - my * cs,
            cx - mx * os, cy - my * os
        };
    }

    auto emitQuad = [&](float ax, float ay, float ac,
                        float bx, float by, float bc,
                        float cx, float cy, float cc,
                        float dx, float dy, float dc) {
        flatVerts_.push_back({ax, ay, stroke.r, stroke.g, stroke.b, stroke.a, ac});
        flatVerts_.push_back({bx, by, stroke.r, stroke.g, stroke.b, stroke.a, bc});
        flatVerts_.push_back({cx, cy, stroke.r, stroke.g, stroke.b, stroke.a, cc});
        flatVerts_.push_back({ax, ay, stroke.r, stroke.g, stroke.b, stroke.a, ac});
        flatVerts_.push_back({cx, cy, stroke.r, stroke.g, stroke.b, stroke.a, cc});
        flatVerts_.push_back({dx, dy, stroke.r, stroke.g, stroke.b, stroke.a, dc});
    };

    const int segs = closed ? n : (n - 1);
    for (int i = 0; i < segs; ++i) {
        const Ring& a = rings[static_cast<size_t>(i)];
        const Ring& b = rings[static_cast<size_t>((i + 1) % n)];
        emitQuad(a.lox, a.loy, 0.0f, a.lix, a.liy, coreCov, b.lix, b.liy, coreCov, b.lox, b.loy, 0.0f);
        if (core > 0.0f) {
            emitQuad(a.lix, a.liy, coreCov, a.rix, a.riy, coreCov, b.rix, b.riy, coreCov, b.lix, b.liy, coreCov);
        }
        emitQuad(a.rix, a.riy, coreCov, a.rox, a.roy, 0.0f, b.rox, b.roy, 0.0f, b.rix, b.riy, coreCov);
    }
}

void UiGlRenderer::addPolyline(
    const float* xy, int count, bool closed,
    float width, float dashOn, float dashOff, bool capRound, Rgba stroke) {
    if (count < 2 || width <= 0.0f || stroke.a <= 0.0f) return;
    // Translucent non-dashed polylines use non-overlapping AA miter ribbons so joins never double-darken.
    if (stroke.a < 0.99f && dashOn <= 0.0f) {
        addPolylineRibbon(xy, count, closed, width, stroke);
        return;
    }
    const int segs = closed ? count : (count - 1);
    float phase = 0.0f;
    const bool roundCaps = capRound && !(dashOn > 0.0f && dashOff > 0.0f);
    for (int i = 0; i < segs; ++i) {
        const int j = (i + 1) % count;
        const float x0 = xy[i * 2];
        const float y0 = xy[i * 2 + 1];
        const float x1 = xy[j * 2];
        const float y1 = xy[j * 2 + 1];
        addSegmentSdf(x0, y0, x1, y1, width, dashOn, dashOff, phase, roundCaps, stroke);
        phase += std::hypot(x1 - x0, y1 - y0);
    }
}

void UiGlRenderer::addPoint(float x, float y, float size, Rgba c) {
    if (c.a <= 0.0f || size <= 0.0f) return;
    pointVerts_.push_back({x, y, c.r, c.g, c.b, c.a, size});
}

void UiGlRenderer::addGradRect(float x, float y, float w, float h, bool vertical, Rgba c0, Rgba c1) {
    if (w <= 0.0f || h <= 0.0f) return;
    const float x1 = x + w;
    const float y1 = y + h;
    const Rgba tr = vertical ? c0 : c1;
    const Rgba br = c1;
    const Rgba bl = vertical ? c1 : c0;
    flatVerts_.push_back({x,  y,  c0.r, c0.g, c0.b, c0.a, 1.0f});
    flatVerts_.push_back({x1, y,  tr.r, tr.g, tr.b, tr.a, 1.0f});
    flatVerts_.push_back({x1, y1, br.r, br.g, br.b, br.a, 1.0f});
    flatVerts_.push_back({x,  y,  c0.r, c0.g, c0.b, c0.a, 1.0f});
    flatVerts_.push_back({x1, y1, br.r, br.g, br.b, br.a, 1.0f});
    flatVerts_.push_back({x,  y1, bl.r, bl.g, bl.b, bl.a, 1.0f});
}

void UiGlRenderer::addCubic(
    float x0, float y0, float c1x, float c1y,
    float c2x, float c2y, float x1, float y1,
    float width, bool capRound, Rgba stroke) {
    constexpr int kSteps = 20;
    float pts[(kSteps + 1) * 2];
    for (int i = 0; i <= kSteps; ++i) {
        const float t = static_cast<float>(i) / static_cast<float>(kSteps);
        const float u = 1.0f - t;
        const float u2 = u * u;
        const float t2 = t * t;
        const float w0 = u2 * u;
        const float w1 = 3.0f * u2 * t;
        const float w2 = 3.0f * u * t2;
        const float w3 = t2 * t;
        pts[i * 2]     = w0 * x0 + w1 * c1x + w2 * c2x + w3 * x1;
        pts[i * 2 + 1] = w0 * y0 + w1 * c1y + w2 * c2y + w3 * y1;
    }
    addPolyline(pts, kSteps + 1, false, width, 0.0f, 0.0f, capRound, stroke);
}

void UiGlRenderer::addArc(
    float cx, float cy, float rx, float ry,
    float startDeg, float sweepDeg, float width,
    bool capRound, bool closedPie, Rgba fill, Rgba stroke) {
    if (rx <= 0.0f || ry <= 0.0f || std::fabs(sweepDeg) <= 0.01f) return;
    const float clampedSweep = std::max(-360.0f, std::min(360.0f, sweepDeg));
    const int steps = std::max(8, std::min(72, static_cast<int>(std::ceil(std::fabs(clampedSweep) / 6.0f))));
    std::vector<float> pts(static_cast<size_t>(steps + 1) * 2u);
    for (int i = 0; i <= steps; ++i) {
        const float a = (startDeg + clampedSweep * (static_cast<float>(i) / static_cast<float>(steps))) *
                        0.017453292519943295f;
        pts[static_cast<size_t>(i) * 2u]     = cx + std::cos(a) * rx;
        pts[static_cast<size_t>(i) * 2u + 1u] = cy + std::sin(a) * ry;
    }
    if (fill.a > 0.001f) {
        if (closedPie) {
            for (int i = 0; i < steps; ++i) {
                addTri(cx, cy,
                       pts[static_cast<size_t>(i) * 2u], pts[static_cast<size_t>(i) * 2u + 1u],
                       pts[static_cast<size_t>(i + 1) * 2u], pts[static_cast<size_t>(i + 1) * 2u + 1u],
                       fill);
            }
        } else {
            addFilledPolygon(pts.data(), steps + 1, fill);
        }
    }
    if (width > 0.0f && stroke.a > 0.001f) {
        addPolyline(pts.data(), steps + 1, false, width, 0.0f, 0.0f, capRound, stroke);
    }
}

void UiGlRenderer::addFilledPolygon(const float* xy, int count, Rgba fill) {
    if (!xy || count < 3 || fill.a <= 0.001f) return;
    struct Pt2 { float x, y; };
    std::vector<Pt2> poly;
    poly.reserve(static_cast<size_t>(count));
    for (int i = 0; i < count; ++i) {
        const float x = xy[i * 2];
        const float y = xy[i * 2 + 1];
        if (poly.empty() || std::hypot(x - poly.back().x, y - poly.back().y) > 1e-3f) {
            poly.push_back({x, y});
        }
    }
    if (poly.size() >= 3 && std::hypot(poly.front().x - poly.back().x, poly.front().y - poly.back().y) <= 1e-3f) {
        poly.pop_back();
    }
    const int n = static_cast<int>(poly.size());
    if (n < 3) return;

    // Signed area determines winding (ensure CCW > 0)
    float area2 = 0.0f;
    for (int i = 0; i < n; ++i) {
        const int j = (i + 1) % n;
        area2 += poly[static_cast<size_t>(i)].x * poly[static_cast<size_t>(j)].y -
                 poly[static_cast<size_t>(j)].x * poly[static_cast<size_t>(i)].y;
    }
    if (std::fabs(area2) < 1e-4f) return;

    std::vector<int> idx(static_cast<size_t>(n));
    for (int i = 0; i < n; ++i) idx[static_cast<size_t>(i)] = (area2 > 0.0f) ? i : (n - 1 - i);

    auto cross = [](const Pt2& a, const Pt2& b, const Pt2& c) -> float {
        return (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x);
    };
    auto pointInTri = [](const Pt2& p, const Pt2& a, const Pt2& b, const Pt2& c) -> bool {
        const float c1 = (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x);
        const float c2 = (c.x - b.x) * (p.y - b.y) - (c.y - b.y) * (p.x - b.x);
        const float c3 = (a.x - c.x) * (p.y - c.y) - (a.y - c.y) * (p.x - c.x);
        return c1 >= -1e-4f && c2 >= -1e-4f && c3 >= -1e-4f;
    };

    int guard = n * 3;
    while (idx.size() > 3 && guard-- > 0) {
        bool clipped = false;
        const int m = static_cast<int>(idx.size());
        for (int i = 0; i < m; ++i) {
            const int i0 = idx[static_cast<size_t>((i + m - 1) % m)];
            const int i1 = idx[static_cast<size_t>(i)];
            const int i2 = idx[static_cast<size_t>((i + 1) % m)];
            const Pt2& a = poly[static_cast<size_t>(i0)];
            const Pt2& b = poly[static_cast<size_t>(i1)];
            const Pt2& c = poly[static_cast<size_t>(i2)];
            if (cross(a, b, c) <= 1e-5f) continue;
            bool inside = false;
            for (int k = 0; k < m; ++k) {
                const int ik = idx[static_cast<size_t>(k)];
                if (ik == i0 || ik == i1 || ik == i2) continue;
                if (pointInTri(poly[static_cast<size_t>(ik)], a, b, c)) {
                    inside = true;
                    break;
                }
            }
            if (!inside) {
                addTri(a.x, a.y, b.x, b.y, c.x, c.y, fill);
                idx.erase(idx.begin() + i);
                clipped = true;
                break;
            }
        }
        if (!clipped) break;
    }
    if (idx.size() == 3) {
        const Pt2& a = poly[static_cast<size_t>(idx[0])];
        const Pt2& b = poly[static_cast<size_t>(idx[1])];
        const Pt2& c = poly[static_cast<size_t>(idx[2])];
        addTri(a.x, a.y, b.x, b.y, c.x, c.y, fill);
    }
}

void UiGlRenderer::addDimmerHole(float w, float h, float l, float t, float r, float b, Rgba dim) {
    if (dim.a <= 0.001f || w <= 0.0f || h <= 0.0f) return;
    const float cl = std::max(0.0f, std::min(w, l));
    const float cr = std::max(cl, std::min(w, r));
    const float ct = std::max(0.0f, std::min(h, t));
    const float cb = std::max(ct, std::min(h, b));
    if (ct > 0.0f) addRect(0.0f, 0.0f, w, ct, dim);
    if (cb < h) addRect(0.0f, cb, w, h - cb, dim);
    if (cl > 0.0f && cb > ct) addRect(0.0f, ct, cl, cb - ct, dim);
    if (cr < w && cb > ct) addRect(cr, ct, w - cr, cb - ct, dim);
}

void UiGlRenderer::addShaderQuad(
    float x, float y, float w, float h, float cornerR, float mode,
    float p0, float p1, float p2, float p3, float p4, float p5,
    Rgba c0, Rgba c1) {
    if (w <= 0.0f || h <= 0.0f) return;
    auto push = [&](float dx, float dy) {
        quadVerts_.push_back({
            x + dx, y + dy, dx, dy,
            w, h, cornerR, mode,
            p0, p1, p2, p3, p4, p5,
            c0.r, c0.g, c0.b, c0.a,
            c1.r, c1.g, c1.b, c1.a
        });
    };
    push(0.0f, 0.0f); push(w, 0.0f); push(w, h);
    push(0.0f, 0.0f); push(w, h);    push(0.0f, h);
}

// ---- Domain plot builders ----

void UiGlRenderer::buildQuarterGraticule(int w, int h) {
    const Rgba grid = argbToRgba(kGridLine);
    const float fw = static_cast<float>(w);
    const float fh = static_cast<float>(h);
    for (int i = 1; i <= 3; ++i) {
        const float x = fw * static_cast<float>(i) / 4.0f;
        const float y = fh * static_cast<float>(i) / 4.0f;
        addSegmentSdf(x, 0.0f, x, fh, 1.0f, 0.0f, 0.0f, 0.0f, false, grid);
        addSegmentSdf(0.0f, y, fw, y, 1.0f, 0.0f, 0.0f, 0.0f, false, grid);
    }
    // 1 px outer border matching Compose drawRect(GridLine, style = Stroke(1f))
    addRoundRect(fw * 0.5f, fh * 0.5f, fw - 1.0f, fh - 1.0f, 0.0f, 1.0f, {0, 0, 0, 0}, grid);
}

void UiGlRenderer::buildHistogramGeometry(
    int w, int h,
    const int32_t* histR, const int32_t* histG,
    const int32_t* histB, const int32_t* histY, int bins) {
    buildQuarterGraticule(w, h);
    if (!histR || !histG || !histB || !histY || bins <= 0) return;

    int32_t peak = 1;
    for (int i = 0; i < bins; ++i) {
        if (histR[i] > peak) peak = histR[i];
        if (histG[i] > peak) peak = histG[i];
        if (histB[i] > peak) peak = histB[i];
        if (histY[i] > peak) peak = histY[i];
    }

    const int cols = std::max(1, w);
    const float fw = static_cast<float>(w);
    const float fh = static_cast<float>(h);
    const float invPeak = 1.0f / static_cast<float>(peak);
    std::vector<float> ys(static_cast<size_t>(cols));

    auto computeHeights = [&](const int32_t* hist) {
        for (int dx = 0; dx < cols; ++dx) {
            const int b0 = (dx * bins) / cols;
            const int b1 = std::min(bins, std::max(b0 + 1, ((dx + 1) * bins) / cols));
            int32_t m = 0;
            for (int b = b0; b < b1; ++b) {
                if (hist[b] > m) m = hist[b];
            }
            ys[static_cast<size_t>(dx)] = fh - (static_cast<float>(m) * invPeak) * fh;
        }
    };

    auto emitFilledChannel = [&](const int32_t* hist, int32_t colorArgb) {
        computeHeights(hist);
        const Rgba c = argbToRgba(colorArgb);
        // Leading half-pixel from x=0 to x=0.5
        addTri(0.0f, fh, 0.5f, ys[0], 0.5f, fh, c);
        for (int dx = 0; dx + 1 < cols; ++dx) {
            const float x0 = static_cast<float>(dx) + 0.5f;
            const float x1 = static_cast<float>(dx + 1) + 0.5f;
            const float y0 = ys[static_cast<size_t>(dx)];
            const float y1 = ys[static_cast<size_t>(dx + 1)];
            addTri(x0, fh, x0, y0, x1, y1, c);
            addTri(x0, fh, x1, y1, x1, fh, c);
        }
        // Trailing half-pixel from cols-0.5 to fw
        const float lx = static_cast<float>(cols - 1) + 0.5f;
        addTri(lx, fh, lx, ys[static_cast<size_t>(cols - 1)], fw, fh, c);
    };

    emitFilledChannel(histR, kHistRed);
    emitFilledChannel(histG, kHistGreen);
    emitFilledChannel(histB, kHistBlue);

    // Luma trace on top as a crisp white line (alpha 0.95, 1.2 px)
    computeHeights(histY);
    std::vector<float> lumaPts(static_cast<size_t>(cols) * 2);
    for (int dx = 0; dx < cols; ++dx) {
        lumaPts[static_cast<size_t>(dx) * 2] = static_cast<float>(dx) + 0.5f;
        lumaPts[static_cast<size_t>(dx) * 2 + 1] = ys[static_cast<size_t>(dx)];
    }
    addPolyline(lumaPts.data(), cols, false, 1.2f, 0.0f, 0.0f, true, argbToRgba(kHistLuma));
}

void UiGlRenderer::buildWaveformGeometry(
    int w, int h,
    const int32_t* wave, int waveW, int waveH, int bins) {
    buildQuarterGraticule(w, h);
    if (!wave || waveW <= 0 || waveH <= 0 || bins <= 0) return;

    const int cols = std::max(1, w);
    const int perCol = std::max(1, waveH * std::max(1, waveW / cols));
    const float invPerCol = 1.0f / static_cast<float>(perCol);
    const float fh = static_cast<float>(h);
    const float invBins = 1.0f / static_cast<float>(bins);
    const Rgba faintC = argbToRgba(kWaveFaint);
    const Rgba brightC = argbToRgba(kWaveBright);

    std::vector<PointVertex> bright;
    bright.reserve(2048);

    for (int dx = 0; dx < cols; ++dx) {
        const int x0 = (dx * waveW) / cols;
        const int x1 = std::min(waveW, std::max(x0 + 1, ((dx + 1) * waveW) / cols));
        const float px = static_cast<float>(dx) + 0.5f;
        for (int level = 0; level < bins; ++level) {
            int32_t count = 0;
            for (int x = x0; x < x1; ++x) {
                count += wave[x * bins + level];
            }
            if (count == 0) continue;
            const float py = fh - (static_cast<float>(level) + 0.5f) * invBins * fh;
            if (static_cast<float>(count) * invPerCol >= 0.25f) {
                bright.push_back({px, py, brightC.r, brightC.g, brightC.b, brightC.a, 1.6f});
            } else {
                addPoint(px, py, 1.6f, faintC);
            }
        }
    }
    pointVerts_.insert(pointVerts_.end(), bright.begin(), bright.end());
}

void UiGlRenderer::buildVectorscopeGeometry(int w, int h, const int32_t* vec, int grid) {
    const Rgba gridC = argbToRgba(kGridLine);
    const float fw = static_cast<float>(w);
    const float fh = static_cast<float>(h);
    addRoundRect(fw * 0.5f, fh * 0.5f, fw - 1.0f, fh - 1.0f, 0.0f, 1.0f, {0, 0, 0, 0}, gridC);

    const float side = std::min(fw, fh);
    const float cx = fw * 0.5f;
    const float cy = fh * 0.5f;
    const float radius = side * 0.5f - 2.0f;
    if (radius <= 4.0f) return;

    addCircle(cx, cy, radius, 1.0f, {0, 0, 0, 0}, gridC);
    addCircle(cx, cy, radius * 0.5f, 1.0f, {0, 0, 0, 0}, gridC);
    addSegmentSdf(cx - radius, cy, cx + radius, cy, 1.0f, 0.0f, 0.0f, 0.0f, false, gridC);
    addSegmentSdf(cx, cy - radius, cx, cy + radius, 1.0f, 0.0f, 0.0f, 0.0f, false, gridC);

    // Six BT.601 primary / secondary target crosses
    const int primaries[6][3] = {
        {255, 0, 0}, {255, 0, 255}, {0, 0, 255},
        {0, 255, 255}, {0, 255, 0}, {255, 255, 0}
    };
    const Rgba targetC = argbToRgba(kVecTarget);
    for (int i = 0; i < 6; ++i) {
        const float u = bt601Cb(primaries[i][0], primaries[i][1], primaries[i][2]);
        const float v = bt601Cr(primaries[i][0], primaries[i][1], primaries[i][2]);
        const float tx = cx + (u / kVecFullScale) * radius;
        const float ty = cy - (v / kVecFullScale) * radius;
        addSegmentSdf(tx - 3.0f, ty, tx + 3.0f, ty, 1.2f, 0.0f, 0.0f, 0.0f, false, targetC);
        addSegmentSdf(tx, ty - 3.0f, tx, ty + 3.0f, 1.2f, 0.0f, 0.0f, 0.0f, false, targetC);
    }

    if (!vec || grid <= 0) return;
    int32_t peak = 1;
    const int total = grid * grid;
    for (int i = 0; i < total; ++i) {
        if (vec[i] > peak) peak = vec[i];
    }
    const float invPeak = 1.0f / static_cast<float>(peak);
    const float invGrid = 1.0f / static_cast<float>(grid);
    const Rgba faintC = argbToRgba(kVecFaint);
    const Rgba brightC = argbToRgba(kVecBright);

    std::vector<PointVertex> bright;
    bright.reserve(512);
    for (int by = 0; by < grid; ++by) {
        const float fy = (static_cast<float>(by) + 0.5f) * invGrid * 2.0f - 1.0f;
        const float py = cy + fy * radius;
        for (int bx = 0; bx < grid; ++bx) {
            const int32_t count = vec[by * grid + bx];
            if (count == 0) continue;
            const float fx = (static_cast<float>(bx) + 0.5f) * invGrid * 2.0f - 1.0f;
            const float px = cx + fx * radius;
            if (static_cast<float>(count) * invPeak >= 0.25f) {
                bright.push_back({px, py, brightC.r, brightC.g, brightC.b, brightC.a, 2.0f});
            } else {
                addPoint(px, py, 2.0f, faintC);
            }
        }
    }
    pointVerts_.insert(pointVerts_.end(), bright.begin(), bright.end());
}

void UiGlRenderer::buildEasingGeometry(
    int w, int h,
    float x1, float y1, float x2, float y2,
    float progress, float density,
    float padL, float padT, float padR, float padB) {
    const float fw = static_cast<float>(w);
    const float fh = static_cast<float>(h);
    const float pw = fw - padL - padR;
    const float ph = fh - padT - padB;
    if (pw <= 1.0f || ph <= 1.0f) return;
    const float left = padL;
    const float top = padT;
    const float bottom = top + ph;
    const float d = std::max(0.5f, density);

    auto sx = [&](float x) { return left + x * pw; };
    auto sy = [&](float y) { return bottom - (y - kEaseYMin) / (kEaseYMax - kEaseYMin) * ph; };

    // 4x4 dashed grid over 0..1 box
    const Rgba gridC = argbToRgba(kEaseGrid);
    const float dashOn = 6.0f * d;
    const float dashOff = 5.0f * d;
    const float gridW = 2.0f * d;
    for (int i = 0; i <= 4; ++i) {
        const float f = static_cast<float>(i) / 4.0f;
        addSegmentSdf(sx(f), sy(0.0f), sx(f), sy(1.0f), gridW, dashOn, dashOff, 0.0f, false, gridC);
        addSegmentSdf(sx(0.0f), sy(f), sx(1.0f), sy(f), gridW, dashOn, dashOff, 0.0f, false, gridC);
    }

    // Playhead vertical line
    const float clampedP = std::max(0.0f, std::min(1.0f, progress));
    const float px = sx(clampedP);
    addSegmentSdf(px, top, px, bottom, 2.0f * d, 0.0f, 0.0f, 0.0f, false, argbToRgba(kEasePlay));

    // Control arms
    const Rgba armC = argbToRgba(kEaseArm);
    addSegmentSdf(sx(0.0f), sy(0.0f), sx(x1), sy(y1), 1.5f * d, 0.0f, 0.0f, 0.0f, true, armC);
    addSegmentSdf(sx(1.0f), sy(1.0f), sx(x2), sy(y2), 1.5f * d, 0.0f, 0.0f, 0.0f, true, armC);

    // Cubic bezier curve (64 segments)
    constexpr int kSteps = 64;
    float curvePts[(kSteps + 1) * 2];
    for (int i = 0; i <= kSteps; ++i) {
        const float s = static_cast<float>(i) / static_cast<float>(kSteps);
        const float u = 1.0f - s;
        const float bx = 3.0f * u * u * s * x1 + 3.0f * u * s * s * x2 + s * s * s;
        const float by = 3.0f * u * u * s * y1 + 3.0f * u * s * s * y2 + s * s * s;
        curvePts[i * 2] = sx(bx);
        curvePts[i * 2 + 1] = sy(by);
    }
    const Rgba whiteC = argbToRgba(kEaseWhite);
    addPolyline(curvePts, kSteps + 1, false, 3.0f * d, 0.0f, 0.0f, true, whiteC);

    // Two control points (outer 13dp white, inner 10dp pink #EA1252)
    const Rgba handleC = argbToRgba(kEaseHandle);
    const float pts[2][2] = {{sx(x1), sy(y1)}, {sx(x2), sy(y2)}};
    for (int i = 0; i < 2; ++i) {
        addCircle(pts[i][0], pts[i][1], 13.0f * d, 0.0f, whiteC, {0, 0, 0, 0});
        addCircle(pts[i][0], pts[i][1], 10.0f * d, 0.0f, handleC, {0, 0, 0, 0});
    }
}

void UiGlRenderer::buildRulerDialGeometry(
    int w, int h,
    float value, float minVal, float maxVal, float step,
    float tickSpacingPx, bool accentNeedle, float density) {
    if (w <= 0 || h <= 0) return;
    const float fw = static_cast<float>(w);
    const float fh = static_cast<float>(h);
    const float d = std::max(0.5f, density);
    const float spacing = std::max(2.0f, tickSpacingPx);
    const float st = std::max(1e-4f, step);
    const float cx = fw * 0.5f;
    const int maxIdx = static_cast<int>(std::round((maxVal - minVal) / st));
    const float clamped = std::max(minVal, std::min(maxVal, value));
    const float idx = (clamped - minVal) / st;
    const int half = static_cast<int>(fw * 0.5f / spacing) + 2;
    const int base = static_cast<int>(std::floor(idx));

    const Rgba majorC = argbToRgba(static_cast<int32_t>(0xFF9A9AA0));
    const Rgba minorC = argbToRgba(static_cast<int32_t>(0xFFC9C9CE));

    for (int k = base - half; k <= base + half; ++k) {
        if (k < 0 || k > maxIdx) continue;
        const float x = cx + (idx - static_cast<float>(k)) * spacing;
        if (x < 0.0f || x > fw) continue;
        const float tickVal = minVal + static_cast<float>(k) * st;
        const bool isMajor = (static_cast<int>(std::round(tickVal / st)) % 5) == 0;
        const float th = isMajor ? fh * 0.75f : fh * 0.42f;
        const float y0 = (fh - th) * 0.5f;
        addSegmentSdf(x, y0, x, y0 + th, 1.5f * d, 0.0f, 0.0f, 0.0f, false, isMajor ? majorC : minorC);
    }

    const Rgba needleC = argbToRgba(static_cast<int32_t>(0xFF18181A));
    const Rgba needleHalo = {needleC.r, needleC.g, needleC.b, 0.20f};
    const float nh = fh * 0.82f;
    const float ns = (fh - nh) * 0.5f;
    addSegmentSdf(cx, ns, cx, ns + nh, 5.5f * d, 0.0f, 0.0f, 0.0f, true, needleHalo);
    addSegmentSdf(cx, ns, cx, ns + nh, 2.0f * d, 0.0f, 0.0f, 0.0f, true, needleC);

    const float cy = fh * 0.5f;
    const float r = 6.0f * d;
    if (accentNeedle) {
        addCircle(cx, cy, r, 1.0f * d,
                  argbToRgba(static_cast<int32_t>(0xFF18D8E0)),
                  argbToRgba(static_cast<int32_t>(0x59000000)));
    } else {
        addCircle(cx, cy, r, 1.2f * d,
                  argbToRgba(static_cast<int32_t>(0xFFFFFFFF)),
                  argbToRgba(static_cast<int32_t>(0x66000000)));
    }
}

void UiGlRenderer::buildPositionPadGeometry(
    int w, int h, float xNorm, float yNorm,
    int32_t dotArgb, int32_t gridArgb, float density) {
    if (w <= 0 || h <= 0) return;
    const float fw = static_cast<float>(w);
    const float fh = static_cast<float>(h);
    const float d = std::max(0.5f, density);
    const Rgba gridC = argbToRgba(gridArgb);
    const Rgba subtle = {gridC.r, gridC.g, gridC.b, gridC.a * 0.45f};
    addSegmentSdf(fw * 0.5f, 0.0f, fw * 0.5f, fh, 1.0f * d, 0.0f, 0.0f, 0.0f, false, gridC);
    addSegmentSdf(0.0f, fh * 0.5f, fw, fh * 0.5f, 1.0f * d, 0.0f, 0.0f, 0.0f, false, gridC);
    for (int i = 1; i <= 3; ++i) {
        if (i == 2) continue;
        const float f = static_cast<float>(i) / 4.0f;
        addSegmentSdf(fw * f, 0.0f, fw * f, fh, 0.75f * d, 0.0f, 0.0f, 0.0f, false, subtle);
        addSegmentSdf(0.0f, fh * f, fw, fh * f, 0.75f * d, 0.0f, 0.0f, 0.0f, false, subtle);
    }
    const float px = std::max(0.0f, std::min(1.0f, xNorm)) * fw;
    const float py = std::max(0.0f, std::min(1.0f, yNorm)) * fh;
    const Rgba dotC = argbToRgba(dotArgb);
    addCircle(px, py, 10.0f * d, 0.0f, {dotC.r, dotC.g, dotC.b, 0.25f}, {0, 0, 0, 0});
    addCircle(px, py, 5.5f * d, 1.5f * d, dotC, argbToRgba(static_cast<int32_t>(0xFFFFFFFF)));
}

void UiGlRenderer::buildCropOverlayGeometry(
    int w, int h, float lNorm, float tNorm, float rNorm, float bNorm, float density) {
    if (w <= 0 || h <= 0) return;
    const float fw = static_cast<float>(w);
    const float fh = static_cast<float>(h);
    const float d = std::max(0.5f, density);
    const float pad = 24.0f * d;
    const float cw = std::max(1.0f, fw - pad * 2.0f);
    const float ch = std::max(1.0f, fh - pad * 2.0f);
    const float l = pad + lNorm * cw;
    const float t = pad + tNorm * ch;
    const float r = pad + rNorm * cw;
    const float b = pad + bNorm * ch;

    // Dimmer outside crop rect (matching crop overlay hole)
    addDimmerHole(fw, fh, l, t, r, b, argbToRgba(static_cast<int32_t>(0x99000000)));

    // Rule-of-thirds guides
    const Rgba thirdC = argbToRgba(static_cast<int32_t>(0x55FFFFFF));
    const float bw = r - l;
    const float bh = b - t;
    for (int k = 1; k <= 2; ++k) {
        const float f = static_cast<float>(k) / 3.0f;
        addSegmentSdf(l + bw * f, t, l + bw * f, b, 1.0f * d, 0.0f, 0.0f, 0.0f, false, thirdC);
        addSegmentSdf(l, t + bh * f, r, t + bh * f, 1.0f * d, 0.0f, 0.0f, 0.0f, false, thirdC);
    }

    // Crisp white border + 8 circle handles
    const float boxPts[8] = {l, t, r, t, r, b, l, b};
    addPolyline(boxPts, 4, true, 1.75f * d, 0.0f, 0.0f, false, argbToRgba(static_cast<int32_t>(0xFFFFFFFF)));
    const float mx = (l + r) * 0.5f;
    const float my = (t + b) * 0.5f;
    const float hx[8] = {l, mx, r, r, r, mx, l, l};
    const float hy[8] = {t, t, t, my, b, b, b, my};
    const Rgba handleFill = argbToRgba(static_cast<int32_t>(0xFFFFFFFF));
    const Rgba handleStroke = argbToRgba(static_cast<int32_t>(0xFF141418));
    for (int i = 0; i < 8; ++i) {
        addCircle(hx[i], hy[i], 5.5f * d, 1.5f * d, handleFill, handleStroke);
    }
}

void UiGlRenderer::buildWaveformStripGeometry(
    int w, int h, const float* peaks, int peakCount,
    int32_t bgTopArgb, int32_t bgBotArgb, int32_t barArgb, float density) {
    if (w <= 0 || h <= 0) return;
    const float fw = static_cast<float>(w);
    const float fh = static_cast<float>(h);
    const float d = std::max(0.5f, density);
    addGradRect(0.0f, 0.0f, fw, fh, true, argbToRgba(bgTopArgb), argbToRgba(bgBotArgb));
    if (!peaks || peakCount <= 0) return;
    const float step = std::max(2.0f * d, fw / static_cast<float>(peakCount));
    const float barW = std::max(1.0f * d, step * 0.62f);
    const float cy = fh * 0.5f;
    const Rgba barC = argbToRgba(barArgb);
    for (int i = 0; i < peakCount; ++i) {
        const float x = (static_cast<float>(i) + 0.5f) * (fw / static_cast<float>(peakCount));
        const float amp = std::max(0.05f, std::min(1.0f, peaks[i]));
        const float bh = std::max(2.0f * d, amp * (fh * 0.78f));
        addRect(x - barW * 0.5f, cy - bh * 0.5f, barW, bh, barC);
    }
}

void UiGlRenderer::buildEasingTileGeometry(
    int w, int h, float x1, float y1, float x2, float y2,
    int32_t strokeArgb, float density) {
    if (w <= 0 || h <= 0) return;
    const float fw = static_cast<float>(w);
    const float fh = static_cast<float>(h);
    const float d = std::max(0.5f, density);
    const float pad = 4.0f * d;
    const float pw = std::max(2.0f, fw - pad * 2.0f);
    const float ph = std::max(2.0f, fh - pad * 2.0f);
    constexpr int kSteps = 24;
    float pts[(kSteps + 1) * 2];
    for (int i = 0; i <= kSteps; ++i) {
        const float t = static_cast<float>(i) / static_cast<float>(kSteps);
        const float u = 1.0f - t;
        const float bx = 3.0f * u * u * t * x1 + 3.0f * u * t * t * x2 + t * t * t;
        const float by = 3.0f * u * u * t * y1 + 3.0f * u * t * t * y2 + t * t * t;
        pts[i * 2]     = pad + bx * pw;
        pts[i * 2 + 1] = pad + (1.0f - by) * ph;
    }
    addPolyline(pts, kSteps + 1, false, 1.8f * d, 0.0f, 0.0f, true, argbToRgba(strokeArgb));
}

void UiGlRenderer::buildVectorCmdGeometry(
    const float* cmds, int cmdFloatCount,
    const int32_t* colors, int colorCount) {
    if (!cmds || cmdFloatCount <= 0) return;
    int p = 0;
    int cp = 0;
    while (p < cmdFloatCount) {
        const int op = static_cast<int>(cmds[p++]);
        switch (op) {
            case UI_CMD_LINE: {
                if (p + 8 > cmdFloatCount || cp + 1 > colorCount) return;
                const float x0 = cmds[p++];
                const float y0 = cmds[p++];
                const float x1 = cmds[p++];
                const float y1 = cmds[p++];
                const float width = cmds[p++];
                const float dashOn = cmds[p++];
                const float dashOff = cmds[p++];
                const bool capRound = cmds[p++] > 0.5f;
                const Rgba c = argbToRgba(colors[cp++]);
                addSegmentSdf(x0, y0, x1, y1, width, dashOn, dashOff, 0.0f, capRound, c);
                break;
            }
            case UI_CMD_POLYLINE: {
                if (p + 6 > cmdFloatCount || cp + 1 > colorCount) return;
                const bool closed = cmds[p++] > 0.5f;
                const float width = cmds[p++];
                const float dashOn = cmds[p++];
                const float dashOff = cmds[p++];
                const bool capRound = cmds[p++] > 0.5f;
                const int n = static_cast<int>(cmds[p++]);
                if (n < 0 || p + n * 2 > cmdFloatCount) return;
                const Rgba c = argbToRgba(colors[cp++]);
                if (n >= 2) {
                    addPolyline(cmds + p, n, closed, width, dashOn, dashOff, capRound, c);
                }
                p += n * 2;
                break;
            }
            case UI_CMD_CIRCLE: {
                if (p + 4 > cmdFloatCount || cp + 2 > colorCount) return;
                const float cx = cmds[p++];
                const float cy = cmds[p++];
                const float r = cmds[p++];
                const float sw = cmds[p++];
                const Rgba fill = argbToRgba(colors[cp++]);
                const Rgba stroke = argbToRgba(colors[cp++]);
                addCircle(cx, cy, r, sw, fill, stroke);
                break;
            }
            case UI_CMD_ROUND_RECT: {
                if (p + 6 > cmdFloatCount || cp + 2 > colorCount) return;
                const float cx = cmds[p++];
                const float cy = cmds[p++];
                const float rw = cmds[p++];
                const float rh = cmds[p++];
                const float cr = cmds[p++];
                const float sw = cmds[p++];
                const Rgba fill = argbToRgba(colors[cp++]);
                const Rgba stroke = argbToRgba(colors[cp++]);
                addRoundRect(cx, cy, rw, rh, cr, sw, fill, stroke);
                break;
            }
            case UI_CMD_RECT: {
                if (p + 4 > cmdFloatCount || cp + 1 > colorCount) return;
                const float x = cmds[p++];
                const float y = cmds[p++];
                const float rw = cmds[p++];
                const float rh = cmds[p++];
                const Rgba fill = argbToRgba(colors[cp++]);
                addRect(x, y, rw, rh, fill);
                break;
            }
            case UI_CMD_CUBIC: {
                if (p + 10 > cmdFloatCount || cp + 1 > colorCount) return;
                const float x0 = cmds[p++];
                const float y0 = cmds[p++];
                const float c1x = cmds[p++];
                const float c1y = cmds[p++];
                const float c2x = cmds[p++];
                const float c2y = cmds[p++];
                const float x1 = cmds[p++];
                const float y1 = cmds[p++];
                const float width = cmds[p++];
                const bool capRound = cmds[p++] > 0.5f;
                const Rgba stroke = argbToRgba(colors[cp++]);
                addCubic(x0, y0, c1x, c1y, c2x, c2y, x1, y1, width, capRound, stroke);
                break;
            }
            case UI_CMD_ARC: {
                if (p + 9 > cmdFloatCount || cp + 2 > colorCount) return;
                const float cx = cmds[p++];
                const float cy = cmds[p++];
                const float rx = cmds[p++];
                const float ry = cmds[p++];
                const float startDeg = cmds[p++];
                const float sweepDeg = cmds[p++];
                const float width = cmds[p++];
                const bool capRound = cmds[p++] > 0.5f;
                const bool closedPie = cmds[p++] > 0.5f;
                const Rgba fill = argbToRgba(colors[cp++]);
                const Rgba stroke = argbToRgba(colors[cp++]);
                addArc(cx, cy, rx, ry, startDeg, sweepDeg, width, capRound, closedPie, fill, stroke);
                break;
            }
            case UI_CMD_FILL_POLY: {
                if (p + 1 > cmdFloatCount || cp + 1 > colorCount) return;
                const int n = static_cast<int>(cmds[p++]);
                if (n < 0 || p + n * 2 > cmdFloatCount) return;
                const Rgba fill = argbToRgba(colors[cp++]);
                if (n >= 3) {
                    addFilledPolygon(cmds + p, n, fill);
                }
                p += n * 2;
                break;
            }
            case UI_CMD_DIMMER_HOLE: {
                if (p + 6 > cmdFloatCount || cp + 1 > colorCount) return;
                const float dw = cmds[p++];
                const float dh = cmds[p++];
                const float l = cmds[p++];
                const float t = cmds[p++];
                const float r = cmds[p++];
                const float b = cmds[p++];
                const Rgba dim = argbToRgba(colors[cp++]);
                addDimmerHole(dw, dh, l, t, r, b, dim);
                break;
            }
            case UI_CMD_SHADER_QUAD: {
                if (p + 12 > cmdFloatCount || cp + 2 > colorCount) return;
                const float qx = cmds[p++];
                const float qy = cmds[p++];
                const float qw = cmds[p++];
                const float qh = cmds[p++];
                const float cr = cmds[p++];
                const float mode = cmds[p++];
                const float p0 = cmds[p++];
                const float p1 = cmds[p++];
                const float p2 = cmds[p++];
                const float p3 = cmds[p++];
                const float p4 = cmds[p++];
                const float p5 = cmds[p++];
                const Rgba c0 = argbToRgba(colors[cp++]);
                const Rgba c1 = argbToRgba(colors[cp++]);
                addShaderQuad(qx, qy, qw, qh, cr, mode, p0, p1, p2, p3, p4, p5, c0, c1);
                break;
            }
            case UI_CMD_GRAD_RECT: {
                if (p + 5 > cmdFloatCount || cp + 2 > colorCount) return;
                const float gx = cmds[p++];
                const float gy = cmds[p++];
                const float gw = cmds[p++];
                const float gh = cmds[p++];
                const bool vertical = cmds[p++] > 0.5f;
                const Rgba c0 = argbToRgba(colors[cp++]);
                const Rgba c1 = argbToRgba(colors[cp++]);
                addGradRect(gx, gy, gw, gh, vertical, c0, c1);
                break;
            }
            case UI_CMD_TEXT: {
                if (p + 9 > cmdFloatCount || cp + 1 > colorCount) return;
                const int entry = static_cast<int>(cmds[p++]);
                const float dstX = cmds[p++];
                const float dstY = cmds[p++];
                const float srcDX = cmds[p++];
                const float srcDY = cmds[p++];
                const float srcW = cmds[p++];
                const float srcH = cmds[p++];
                const float dstW = cmds[p++];
                const float dstH = cmds[p++];
                const Rgba tint = argbToRgba(colors[cp++]);
                addTextBlit(entry, dstX, dstY, srcDX, srcDY, srcW, srcH, dstW, dstH, tint);
                break;
            }
            default:
                return;
        }
    }
}

// ---- Public draw entry points ----

bool UiGlRenderer::drawScopeHistogram(
    int surfaceId,
    const int32_t* histR, const int32_t* histG,
    const int32_t* histB, const int32_t* histY, int bins) {
    int w = 0, h = 0;
    EGLSurface surf = EGL_NO_SURFACE;
    if (!beginSurface(surfaceId, w, h, surf)) return false;
    clearColor(kPlotBg);
    resetBatches();
    buildHistogramGeometry(w, h, histR, histG, histB, histY, bins);
    flushBatches(w, h);
    return egl_.swap(surf, -1);
}

bool UiGlRenderer::drawScopeWaveform(
    int surfaceId,
    const int32_t* wave, int waveW, int waveH, int bins) {
    int w = 0, h = 0;
    EGLSurface surf = EGL_NO_SURFACE;
    if (!beginSurface(surfaceId, w, h, surf)) return false;
    clearColor(kPlotBg);
    resetBatches();
    buildWaveformGeometry(w, h, wave, waveW, waveH, bins);
    flushBatches(w, h);
    return egl_.swap(surf, -1);
}

bool UiGlRenderer::drawScopeVectorscope(int surfaceId, const int32_t* vec, int grid) {
    int w = 0, h = 0;
    EGLSurface surf = EGL_NO_SURFACE;
    if (!beginSurface(surfaceId, w, h, surf)) return false;
    clearColor(kPlotBg);
    resetBatches();
    buildVectorscopeGeometry(w, h, vec, grid);
    flushBatches(w, h);
    return egl_.swap(surf, -1);
}

bool UiGlRenderer::drawEasingGraph(
    int surfaceId,
    float x1, float y1, float x2, float y2,
    float progress, float density,
    float padL, float padT, float padR, float padB) {
    int w = 0, h = 0;
    EGLSurface surf = EGL_NO_SURFACE;
    if (!beginSurface(surfaceId, w, h, surf)) return false;
    clearColor(0x00000000);
    resetBatches();
    buildEasingGeometry(w, h, x1, y1, x2, y2, progress, density, padL, padT, padR, padB);
    flushBatches(w, h);
    return egl_.swap(surf, -1);
}

bool UiGlRenderer::drawRulerDial(
    int surfaceId,
    float value, float minVal, float maxVal, float step,
    float tickSpacingPx, bool accentNeedle, float density) {
    int w = 0, h = 0;
    EGLSurface surf = EGL_NO_SURFACE;
    if (!beginSurface(surfaceId, w, h, surf)) return false;
    clearColor(0x00000000);
    resetBatches();
    buildRulerDialGeometry(w, h, value, minVal, maxVal, step, tickSpacingPx, accentNeedle, density);
    flushBatches(w, h);
    return egl_.swap(surf, -1);
}

bool UiGlRenderer::drawPositionPad(
    int surfaceId,
    float xNorm, float yNorm,
    int32_t dotArgb, int32_t gridArgb, float density) {
    int w = 0, h = 0;
    EGLSurface surf = EGL_NO_SURFACE;
    if (!beginSurface(surfaceId, w, h, surf)) return false;
    clearColor(0x00000000);
    resetBatches();
    buildPositionPadGeometry(w, h, xNorm, yNorm, dotArgb, gridArgb, density);
    flushBatches(w, h);
    return egl_.swap(surf, -1);
}

bool UiGlRenderer::drawCropOverlay(
    int surfaceId,
    float lNorm, float tNorm, float rNorm, float bNorm,
    float density) {
    int w = 0, h = 0;
    EGLSurface surf = EGL_NO_SURFACE;
    if (!beginSurface(surfaceId, w, h, surf)) return false;
    clearColor(0x00000000);
    resetBatches();
    buildCropOverlayGeometry(w, h, lNorm, tNorm, rNorm, bNorm, density);
    flushBatches(w, h);
    return egl_.swap(surf, -1);
}

bool UiGlRenderer::drawWaveformStrip(
    int surfaceId,
    const float* peaks, int peakCount,
    int32_t bgTopArgb, int32_t bgBotArgb, int32_t barArgb, float density) {
    int w = 0, h = 0;
    EGLSurface surf = EGL_NO_SURFACE;
    if (!beginSurface(surfaceId, w, h, surf)) return false;
    clearColor(0x00000000);
    resetBatches();
    buildWaveformStripGeometry(w, h, peaks, peakCount, bgTopArgb, bgBotArgb, barArgb, density);
    flushBatches(w, h);
    return egl_.swap(surf, -1);
}

bool UiGlRenderer::drawEasingTile(
    int surfaceId,
    float x1, float y1, float x2, float y2,
    int32_t strokeArgb, float density) {
    int w = 0, h = 0;
    EGLSurface surf = EGL_NO_SURFACE;
    if (!beginSurface(surfaceId, w, h, surf)) return false;
    clearColor(0x00000000);
    resetBatches();
    buildEasingTileGeometry(w, h, x1, y1, x2, y2, strokeArgb, density);
    flushBatches(w, h);
    return egl_.swap(surf, -1);
}

bool UiGlRenderer::drawVectorCommands(
    int surfaceId,
    int32_t clearArgb,
    const float* cmds, int cmdFloatCount,
    const int32_t* colors, int colorCount) {
    int w = 0, h = 0;
    EGLSurface surf = EGL_NO_SURFACE;
    if (!beginSurface(surfaceId, w, h, surf)) return false;
    clearColor(clearArgb);
    resetBatches();
    buildVectorCmdGeometry(cmds, cmdFloatCount, colors, colorCount);
    flushBatches(w, h);
    return egl_.swap(surf, -1);
}

bool UiGlRenderer::runOffscreenSelfTest(int w, int h, int32_t outCounts[4]) {
    if (!initialized_ || w <= 0 || h <= 0 || !outCounts) return false;
    outCounts[0] = outCounts[1] = outCounts[2] = outCounts[3] = 0;

    if (!egl_.makeCurrent(egl_.bootstrapSurface())) return false;
    currentSurf_ = egl_.bootstrapSurface();

    GLuint fbo = 0, rbo = 0;
    glGenFramebuffers(1, &fbo);
    glGenRenderbuffers(1, &rbo);
    glBindRenderbuffer(GL_RENDERBUFFER, rbo);
    glRenderbufferStorage(GL_RENDERBUFFER, GL_RGBA8, w, h);
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, rbo);

    bool ok = (glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE);
    if (ok) {
        glViewport(0, 0, w, h);
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glEnable(GL_BLEND);
        glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);

        std::vector<uint8_t> rgba(static_cast<size_t>(w) * static_cast<size_t>(h) * 4u);

        auto countNonBg = [&]() -> int32_t {
            glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, rgba.data());
            int32_t n = 0;
            for (size_t i = 0; i < rgba.size(); i += 4) {
                if (rgba[i] > 28 || rgba[i + 1] > 28 || rgba[i + 2] > 28) ++n;
            }
            return n;
        };
        auto countNonTransparent = [&]() -> int32_t {
            glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, rgba.data());
            int32_t n = 0;
            for (size_t i = 0; i < rgba.size(); i += 4) {
                if (rgba[i + 3] > 16) ++n;
            }
            return n;
        };

        // 1. Histogram check
        int32_t hR[256] = {0}, hG[256] = {0}, hB[256] = {0}, hY[256] = {0};
        for (int i = 40; i < 220; ++i) {
            hR[i] = 100;
            hG[i] = 80;
            hB[i] = 60;
            hY[i] = 90;
        }
        clearColor(kPlotBg);
        resetBatches();
        buildHistogramGeometry(w, h, hR, hG, hB, hY, 256);
        flushBatches(w, h);
        outCounts[0] = countNonBg();

        // 2. Waveform + Vectorscope phosphor check
        std::vector<int32_t> wave(static_cast<size_t>(32) * 256u, 0);
        for (int x = 0; x < 32; ++x) wave[static_cast<size_t>(x) * 256u + 128u] = 20;
        std::vector<int32_t> vec(96u * 96u, 0);
        vec[48u * 96u + 48u] = 50;
        vec[32u * 96u + 64u] = 30;
        clearColor(kPlotBg);
        resetBatches();
        buildWaveformGeometry(w, h, wave.data(), 32, 32, 256);
        buildVectorscopeGeometry(w, h, vec.data(), 96);
        flushBatches(w, h);
        outCounts[1] = countNonBg();

        // 3. Easing graph check
        clearColor(0x00000000);
        resetBatches();
        buildEasingGeometry(w, h, 0.42f, 0.0f, 0.58f, 1.0f, 0.5f, 2.0f, 24.0f, 8.0f, 8.0f, 20.0f);
        flushBatches(w, h);
        outCounts[2] = countNonTransparent();

        // 4. Vector command stream check (dashed quad + round rect + circle)
        // Build 249: plus atlas text — a generated 64x64 all-coverage atlas
        // with one entry; an out-of-range entry must skip, the valid blit
        // must land (its pixels count toward outCounts[3] like the rest).
        {
            std::vector<uint8_t> px(64u * 64u * 4u, 255);
            const int32_t rects[] = {0, 0, 64, 64};
            setAtlas(px.data(), 64, 64, rects, 1);
        }
        const float cmds[] = {
            static_cast<float>(UI_CMD_POLYLINE), 1.0f, 3.0f, 8.0f, 4.0f, 0.0f, 4.0f,
            16.0f, 16.0f, static_cast<float>(w - 16), 16.0f,
            static_cast<float>(w - 16), static_cast<float>(h - 16), 16.0f, static_cast<float>(h - 16),
            static_cast<float>(UI_CMD_ROUND_RECT),
            static_cast<float>(w) * 0.5f, static_cast<float>(h) * 0.5f, 20.0f, 20.0f, 4.0f, 3.0f,
            static_cast<float>(UI_CMD_CIRCLE),
            static_cast<float>(w) * 0.5f, 10.0f, 8.0f, 2.0f,
            static_cast<float>(UI_CMD_TEXT),
            99.0f, 4.0f, 4.0f, 0.0f, 0.0f, 64.0f, 64.0f, 0.0f, 0.0f,
            static_cast<float>(UI_CMD_TEXT),
            0.0f, 4.0f, 4.0f, 0.0f, 0.0f, 64.0f, 64.0f, 0.0f, 0.0f
        };
        const int32_t cols[] = {
            static_cast<int32_t>(0xFF3EB1F7),
            static_cast<int32_t>(0xFFFFFFFF), static_cast<int32_t>(0xFF1689F5),
            static_cast<int32_t>(0xFFD2FC32), static_cast<int32_t>(0xFF111114),
            static_cast<int32_t>(0xFFFFFFFF), static_cast<int32_t>(0xFFFFFFFF)
        };
        clearColor(0x00000000);
        resetBatches();
        buildVectorCmdGeometry(
            cmds, static_cast<int>(sizeof(cmds) / sizeof(cmds[0])),
            cols, static_cast<int>(sizeof(cols) / sizeof(cols[0])));
        flushBatches(w, h);
        outCounts[3] = countNonTransparent();
    }

    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glBindRenderbuffer(GL_RENDERBUFFER, 0);
    glDeleteFramebuffers(1, &fbo);
    glDeleteRenderbuffers(1, &rbo);
    return ok;
}

}  // namespace am
