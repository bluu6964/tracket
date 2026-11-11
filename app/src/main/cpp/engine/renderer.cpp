#include "renderer.h"

#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>
#include <android/log.h>
#include <android/native_window.h>

#include <cmath>
#include <vector>

#include "gl_math.h"
#include "quad_warp.h"

#define TAG "AmRenderer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace am {
namespace {

const char* kVertex = R"(#version 300 es
layout(location = 0) in vec2 aPos;          // quad corners, -0.5 .. 0.5, Y down
uniform mat4 uMvp;
uniform mat4 uTex;
out vec2 vUv;
void main() {
    gl_Position = uMvp * vec4(aPos, 0.0, 1.0);
    vec2 uv = vec2(aPos.x + 0.5, 0.5 - aPos.y);   // picture origin = bottom-left (GL / SurfaceTexture)
    vUv = (uTex * vec4(uv, 0.0, 1.0)).xy;
}
)";

const char* kFragmentOes = R"(#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
uniform samplerExternalOES uSampler;
uniform float uOpacity;
in vec2 vUv;
out vec4 oColor;
void main() {
    vec4 c = texture(uSampler, vUv);
    oColor = vec4(c.rgb * uOpacity, c.a * uOpacity);   // premultiplied alpha
}
)";

const char* kFragmentFlat = R"(#version 300 es
precision mediump float;
uniform sampler2D uSampler;
uniform float uOpacity;
uniform float uGain;                                   // brightness factor (Remove patch), 1 = as is
in vec2 vUv;
out vec4 oColor;
void main() {
    vec4 c = texture(uSampler, vUv);                   // texture is premultiplied
    c.rgb = min(c.rgb * uGain, vec3(c.a));
    oColor = c * uOpacity;
}
)";


const char* kBlendVertex = R"(#version 300 es
layout(location = 0) in vec2 aPos;           // quad corners -0.5 .. 0.5
out vec2 vUv;
void main() {
    gl_Position = vec4(aPos * 2.0, 0.0, 1.0);  // full screen
    vUv = aPos + 0.5;                          // (0,0) = bottom-left, same as the frame buffer
}
)";

// Same maths as BlendEffect.kt (W3C compositing formulas), so export == preview look.
const char* kBlendFragment = R"(#version 300 es
precision highp float;
uniform sampler2D uBackdrop;   // what is on the frame so far (premultiplied)
uniform sampler2D uLayer;      // the layer, premultiplied, canvas sized
uniform float uMode;
uniform float uOpacity;
uniform vec3 uCanvas;
uniform float uFlip;           // 1 = layer rows start at the TOP (Bitmap)
uniform float uGroup;          // 1 = the frame is a group picture: the backdrop can be (partly) transparent
in vec2 vUv;
out vec4 oColor;

float lum(vec3 c) { return dot(c, vec3(0.3, 0.59, 0.11)); }
vec3 clipColor(vec3 c) {
    float l = lum(c);
    float n = min(min(c.r, c.g), c.b);
    float x = max(max(c.r, c.g), c.b);
    if (n < 0.0) { c = l + (c - l) * l / (l - n); }
    if (x > 1.0) { c = l + (c - l) * (1.0 - l) / (x - l); }
    return c;
}
vec3 setLum(vec3 c, float l) { return clipColor(c + (l - lum(c))); }
float sat(vec3 c) { return max(max(c.r, c.g), c.b) - min(min(c.r, c.g), c.b); }
vec3 setSat(vec3 c, float s) {
    float mx = max(max(c.r, c.g), c.b);
    float mn = min(min(c.r, c.g), c.b);
    if (mx > mn) { return (c - mn) * s / (mx - mn); }
    return vec3(0.0);
}
float overlayCh(float b, float s) { return b <= 0.5 ? 2.0 * b * s : 1.0 - 2.0 * (1.0 - b) * (1.0 - s); }
float softCh(float b, float s) {
    if (s <= 0.5) { return b - (1.0 - 2.0 * s) * b * (1.0 - b); }
    float d = b <= 0.25 ? ((16.0 * b - 12.0) * b + 4.0) * b : sqrt(b);
    return b + (2.0 * s - 1.0) * (d - b);
}
float burnCh(float b, float s) {
    if (b >= 1.0) { return 1.0; }
    if (s <= 0.0) { return 0.0; }
    return 1.0 - min(1.0, (1.0 - b) / s);
}
float dodgeCh(float b, float s) {
    if (b <= 0.0) { return 0.0; }
    if (s >= 1.0) { return 1.0; }
    return min(1.0, b / (1.0 - s));
}
vec3 overlayV(vec3 b, vec3 s) { return vec3(overlayCh(b.r, s.r), overlayCh(b.g, s.g), overlayCh(b.b, s.b)); }
vec3 softV(vec3 b, vec3 s) { return vec3(softCh(b.r, s.r), softCh(b.g, s.g), softCh(b.b, s.b)); }
vec3 burnV(vec3 b, vec3 s) { return vec3(burnCh(b.r, s.r), burnCh(b.g, s.g), burnCh(b.b, s.b)); }
vec3 dodgeV(vec3 b, vec3 s) { return vec3(dodgeCh(b.r, s.r), dodgeCh(b.g, s.g), dodgeCh(b.b, s.b)); }

// b = backdrop colour, s = layer colour (both straight, not premultiplied).
vec3 blendFn(int m, vec3 b, vec3 s) {
    if (m == 10) { return min(b, s); }
    if (m == 11) { return b * s; }
    if (m == 12) { return burnV(b, s); }
    if (m == 20) { return max(b, s); }
    if (m == 21) { return b + s - b * s; }
    if (m == 22) { return dodgeV(b, s); }
    if (m == 23) { return min(b + s, vec3(1.0)); }
    if (m == 30) { return overlayV(b, s); }
    if (m == 31) { return softV(b, s); }
    if (m == 32) { return overlayV(s, b); }
    if (m == 40) { return abs(b - s); }
    if (m == 41) { return b + s - 2.0 * b * s; }
    if (m == 50) { return setLum(setSat(s, sat(b)), lum(b)); }
    if (m == 51) { return setLum(setSat(b, sat(s)), lum(b)); }
    if (m == 52) { return setLum(s, lum(b)); }
    if (m == 53) { return setLum(b, lum(s)); }
    return s;
}

void main() {
    vec4 bd = texture(uBackdrop, vUv);
    vec4 o = texture(uLayer, vec2(vUv.x, uFlip > 0.5 ? 1.0 - vUv.y : vUv.y));
    int mode = int(uMode + 0.5);
    if (uGroup > 0.5) {
        // Inside a group: general W3C compositing, the backdrop is the group's own (premultiplied, maybe transparent) picture.
        float ab = bd.a;
        vec3 cb = ab > 0.0001 ? bd.rgb / ab : vec3(0.0);
        if (mode >= 1 && mode <= 4) {
            float lp0 = dot(o.rgb, vec3(0.2126, 0.7152, 0.0722));
            float m0 = 1.0;
            if (mode == 1) { m0 = 1.0 - uOpacity * (1.0 - o.a); }
            else if (mode == 2) { m0 = 1.0 - uOpacity * o.a; }
            else if (mode == 3) { m0 = 1.0 - uOpacity + uOpacity * lp0; }
            else { m0 = 1.0 - uOpacity * lp0; }
            oColor = bd * m0;   // the layer cuts what lies beneath it inside the group
            return;
        }
        float as = o.a * uOpacity;
        vec3 cs = o.a > 0.0001 ? o.rgb / o.a : vec3(0.0);
        vec3 bl = mode == 0 ? cs : blendFn(mode, cb, cs);
        vec3 co = (1.0 - ab) * as * cs + as * ab * bl + (1.0 - as) * bd.rgb;
        oColor = vec4(co, as + ab * (1.0 - as));
        return;
    }
    float outA = bd.a;
    vec3 b = bd.a > 0.0001 ? bd.rgb / bd.a : uCanvas;

    if (mode >= 1 && mode <= 4) {
        // Matte: the layer cuts what lies beneath it; the canvas colour stays.
        float lp = dot(o.rgb, vec3(0.2126, 0.7152, 0.0722));
        float m = 1.0;
        if (mode == 1) { m = 1.0 - uOpacity * (1.0 - o.a); }
        else if (mode == 2) { m = 1.0 - uOpacity * o.a; }
        else if (mode == 3) { m = 1.0 - uOpacity + uOpacity * lp; }
        else { m = 1.0 - uOpacity * lp; }
        oColor = vec4(mix(uCanvas, b, m) * outA, outA);
        return;
    }
    float a = o.a;
    vec3 s = a > 0.0001 ? o.rgb / a : vec3(0.0);
    a = a * uOpacity;
    vec3 f = mode == 0 ? s : blendFn(mode, b, s);
    vec3 result = (1.0 - a) * b + a * f;
    oColor = vec4(result * outA, outA);
}
)";

uint32_t compile(GLenum type, const char* src) {
    const GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &src, nullptr);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[512] = {0};
        glGetShaderInfoLog(s, sizeof(log) - 1, nullptr, log);
        LOGE("shader compile failed: %s", log);
        glDeleteShader(s);
        return 0;
    }
    return s;
}

}  // namespace

bool Renderer::init() {
    if (ok_) return true;
    if (!egl_.init()) return false;
    if (!buildPrograms()) {
        egl_.release();
        return false;
    }
    const float quad[8] = {-0.5f, -0.5f, 0.5f, -0.5f, -0.5f, 0.5f, 0.5f, 0.5f};
    glGenVertexArrays(1, &vao_);
    glBindVertexArray(vao_);
    glGenBuffers(1, &vbo_);
    glBindBuffer(GL_ARRAY_BUFFER, vbo_);
    glBufferData(GL_ARRAY_BUFFER, sizeof(quad), quad, GL_STATIC_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, nullptr);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_CULL_FACE);
    glEnable(GL_BLEND);
    glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);  // premultiplied "normal" blending
    out_ = egl_.bootstrapSurface();
    w_ = h_ = 1;
    ok_ = true;
    const char* r = (const char*)glGetString(GL_RENDERER);
    LOGI("renderer ready: %s", r ? r : "?");
    return true;
}

bool Renderer::buildPrograms() {
    const struct { Program* p; const char* fs; } defs[] = {{&oes_, kFragmentOes}, {&flat_, kFragmentFlat}};
    for (auto& d : defs) {
        const uint32_t vs = compile(GL_VERTEX_SHADER, kVertex);
        const uint32_t fs = compile(GL_FRAGMENT_SHADER, d.fs);
        if (!vs || !fs) return false;
        const uint32_t prog = glCreateProgram();
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
            LOGE("program link failed: %s", log);
            return false;
        }
        d.p->id = prog;
        d.p->mvp = glGetUniformLocation(prog, "uMvp");
        d.p->tex = glGetUniformLocation(prog, "uTex");
        d.p->sampler = glGetUniformLocation(prog, "uSampler");
        d.p->opacity = glGetUniformLocation(prog, "uOpacity");
        d.p->gain = glGetUniformLocation(prog, "uGain");
        if (d.p->gain >= 0) {
            glUseProgram(prog);
            glUniform1f(d.p->gain, 1.f);
        }
    }
    // A broken blend shader must not stop plain exports: blended layers then fall back to Normal.
    if (!buildBlendProgram()) LOGE("blend program unavailable, blend modes fall back to Normal");
    return true;
}

bool Renderer::buildBlendProgram() {
    const uint32_t vs = compile(GL_VERTEX_SHADER, kBlendVertex);
    const uint32_t fs = compile(GL_FRAGMENT_SHADER, kBlendFragment);
    if (!vs || !fs) return false;
    const uint32_t prog = glCreateProgram();
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
        LOGE("blend program link failed: %s", log);
        glDeleteProgram(prog);
        return false;
    }
    blendProg_ = prog;
    bBackdrop_ = glGetUniformLocation(prog, "uBackdrop");
    bLayer_ = glGetUniformLocation(prog, "uLayer");
    bMode_ = glGetUniformLocation(prog, "uMode");
    bOpacity_ = glGetUniformLocation(prog, "uOpacity");
    bCanvas_ = glGetUniformLocation(prog, "uCanvas");
    bFlip_ = glGetUniformLocation(prog, "uFlip");
    bGroup_ = glGetUniformLocation(prog, "uGroup");
    return true;
}

void Renderer::release() {
    if (!ok_) return;
    egl_.makeCurrent(egl_.bootstrapSurface());
    dropOutput();
    releaseFx();
    releaseGroupTargets();
    if (oes_.id) glDeleteProgram(oes_.id);
    if (flat_.id) glDeleteProgram(flat_.id);
    if (blendProg_) glDeleteProgram(blendProg_);
    if (fboLayer_) glDeleteFramebuffers(1, &fboLayer_);
    if (texLayer_) glDeleteTextures(1, &texLayer_);
    if (texBackdrop_) glDeleteTextures(1, &texBackdrop_);
    blendProg_ = fboLayer_ = texLayer_ = texBackdrop_ = 0;
    scratchW_ = scratchH_ = 0;
    if (vbo_) glDeleteBuffers(1, &vbo_);
    if (vao_) glDeleteVertexArrays(1, &vao_);
    egl_.release();
    ok_ = false;
}

uint32_t Renderer::createTexture(bool oes) {
    uint32_t t = 0;
    glGenTextures(1, &t);
    const GLenum target = oes ? GL_TEXTURE_EXTERNAL_OES : GL_TEXTURE_2D;
    glBindTexture(target, t);
    glTexParameteri(target, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(target, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(target, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(target, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    return t;
}

bool Renderer::uploadRgba(uint32_t tex, int w, int h, const uint8_t* rgba) {
    if (!ok_ || !tex || w <= 0 || h <= 0 || !rgba) return false;
    glBindTexture(GL_TEXTURE_2D, tex);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, rgba);
    return glGetError() == GL_NO_ERROR;
}

void Renderer::deleteTexture(uint32_t tex) {
    if (tex) glDeleteTextures(1, &tex);
}

void Renderer::dropOutput() {
    if (out_ != EGL_NO_SURFACE && out_ != egl_.bootstrapSurface()) egl_.destroySurface(out_);
    out_ = egl_.bootstrapSurface();
    outIsWindow_ = outIsPbuffer_ = false;
}

void Renderer::useOutput(EGLSurface s, bool window) {
    out_ = s;
    outIsWindow_ = window;
    outIsPbuffer_ = !window && s != egl_.bootstrapSurface();
    egl_.makeCurrent(out_);
    if (out_ == EGL_NO_SURFACE || out_ == egl_.bootstrapSurface()) {
        w_ = h_ = 1;
    } else {
        w_ = egl_.width(out_);
        h_ = egl_.height(out_);
    }
}

bool Renderer::setOutputWindow(ANativeWindow* window) {
    if (!ok_) return false;
    egl_.makeCurrent(egl_.bootstrapSurface());  // release the old surface before destroying it
    dropOutput();
    if (!window) {
        useOutput(egl_.bootstrapSurface(), false);
        return true;
    }
    EGLSurface s = egl_.createWindowSurface(window);
    if (s == EGL_NO_SURFACE) {
        useOutput(egl_.bootstrapSurface(), false);
        return false;
    }
    useOutput(s, true);
    return true;
}

bool Renderer::setOutputOffscreen(int w, int h) {
    if (!ok_) return false;
    egl_.makeCurrent(egl_.bootstrapSurface());
    dropOutput();
    EGLSurface s = egl_.createPbufferSurface(w, h);
    if (s == EGL_NO_SURFACE) {
        useOutput(egl_.bootstrapSurface(), false);
        return false;
    }
    useOutput(s, false);
    return true;
}

void Renderer::beginFrame(float r, float g, float b, float a) {
    grpDepth_ = 0;       // a group left open by an error never leaks into the next frame
    targetFbo_ = 0;
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glViewport(0, 0, w_, h_);
    // Final-frame clip: for the whole frame composition (layers, blend scratch, group targets — all
    // canvas-sized) a scissor on the canvas rect is kept enabled, so nothing a layer draws can land
    // outside the canvas (0, 0, w_, h_). applyFx disables it while it renders into picture-sized
    // targets (a layer's own picture can be larger than the canvas) and restores it on exit.
    glScissor(0, 0, w_, h_);
    glEnable(GL_SCISSOR_TEST);
    glClearColor(r * a, g * a, b * a, a);
    glClear(GL_COLOR_BUFFER_BIT);
}

Mat4 layerModelFor(const LayerDraw& d, int w, int h) {
    const float cw = std::fmin(1.f, std::fmax(0.01f, d.cropW));
    const float ch = std::fmin(1.f, std::fmax(0.01f, d.cropH));
    if (d.is3d()) {
        // Turned in 3D: perspective makes w != 1, z is dropped (painter's order, no depth buffer).
        return Mat4::flattenZ() * layerModel3d((float)w, (float)h, (float)d.contentW * cw, (float)d.contentH * ch, d.baseX,
                                               d.baseY, d.pivotX, d.pivotY, d.scaleX, d.scaleY, d.rotationDeg, d.rotX,
                                               d.rotY, d.perspD);
    }
    return layerModel((float)w, (float)h, (float)d.contentW * cw, (float)d.contentH * ch, d.offsetX, d.offsetY, d.scaleX,
                      d.scaleY, d.rotationDeg);
}

void Renderer::drawLayer(const LayerDraw& d) {
    if (!ok_ || d.texture == 0 || d.contentW <= 0 || d.contentH <= 0) return;
    const float cw = std::fmin(1.f, std::fmax(0.01f, d.cropW));
    const float ch = std::fmin(1.f, std::fmax(0.01f, d.cropH));
    const float cx = std::fmin(1.f - cw, std::fmax(0.f, d.cropX));
    const float cy = std::fmin(1.f - ch, std::fmax(0.f, d.cropY));
    const Program& p = d.oes ? oes_ : flat_;
    Mat4 mvp;
    if (d.pin) {
        Mat4 pm;
        if (!pinModel(d.pinQ, &pm)) return;   // degenerate quad (surface lost / folded): nothing to draw
        mvp = Mat4::canvas((float)w_, (float)h_) * pm;
    } else {
        mvp = Mat4::canvas((float)w_, (float)h_) * layerModelFor(d, w_, h_);
    }
    // Quad uv (0..1, v up) -> the cropped window of the upright picture (cy counts from the top).
    const Mat4 crop = Mat4::translate(cx, 1.f - cy - ch) * Mat4::scale(cw, ch);
    const Mat4 tex = layerTexMatrix(d.texMatrix, d.contentRotation) * crop;
    glUseProgram(p.id);
    glUniformMatrix4fv(p.mvp, 1, GL_FALSE, mvp.m);
    glUniformMatrix4fv(p.tex, 1, GL_FALSE, tex.m);
    glUniform1f(p.opacity, d.opacity);
    if (p.gain >= 0) glUniform1f(p.gain, d.gain);
    glUniform1i(p.sampler, 0);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(d.oes ? GL_TEXTURE_EXTERNAL_OES : GL_TEXTURE_2D, d.texture);
    glBindVertexArray(vao_);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
}


// Canvas-sized scratch buffers for the blend pass: [texLayer_ + fboLayer_] the layer drawn alone,
// [texBackdrop_] a copy of the frame so far. Needs a current GL context with the output selected.
bool Renderer::ensureScratch() {
    if (!blendProg_ || w_ < 2 || h_ < 2) return false;
    if (fboLayer_ && scratchW_ == w_ && scratchH_ == h_) return true;
    if (fboLayer_) glDeleteFramebuffers(1, &fboLayer_);
    if (texLayer_) glDeleteTextures(1, &texLayer_);
    if (texBackdrop_) glDeleteTextures(1, &texBackdrop_);
    fboLayer_ = texLayer_ = texBackdrop_ = 0;
    scratchW_ = scratchH_ = 0;
    auto make = [&](uint32_t& t) {
        glGenTextures(1, &t);
        glBindTexture(GL_TEXTURE_2D, t);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w_, h_, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    };
    make(texLayer_);
    make(texBackdrop_);
    glGenFramebuffers(1, &fboLayer_);
    glBindFramebuffer(GL_FRAMEBUFFER, fboLayer_);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texLayer_, 0);
    const bool complete = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
    glBindFramebuffer(GL_FRAMEBUFFER, targetFbo_);  // back to where the frame is drawn (a group's target inside a group pass)
    if (!complete) {
        LOGE("blend scratch framebuffer incomplete (%dx%d)", w_, h_);
        glDeleteFramebuffers(1, &fboLayer_);
        glDeleteTextures(1, &texLayer_);
        glDeleteTextures(1, &texBackdrop_);
        fboLayer_ = texLayer_ = texBackdrop_ = 0;
        return false;
    }
    scratchW_ = w_;
    scratchH_ = h_;
    return true;
}

// Combines layerTex with the frame buffer (default framebuffer = the output) and writes the result back.
void Renderer::composeBlend(uint32_t layerTex, bool flipV, int mode, float opacity, const float* rgb) {
    glBindFramebuffer(GL_FRAMEBUFFER, targetFbo_);
    glViewport(0, 0, w_, h_);
    // Backdrop = copy of the frame so far (the shader cannot read the buffer it writes to).
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, texBackdrop_);
    glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 0, 0, w_, h_);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, layerTex);
    glDisable(GL_BLEND);  // the shader writes the final colour
    glUseProgram(blendProg_);
    glUniform1i(bBackdrop_, 1);
    glUniform1i(bLayer_, 0);
    glUniform1f(bMode_, (float)mode);
    glUniform1f(bOpacity_, opacity);
    glUniform3f(bCanvas_, rgb ? rgb[0] : 0.f, rgb ? rgb[1] : 0.f, rgb ? rgb[2] : 0.f);
    glUniform1f(bFlip_, flipV ? 1.f : 0.f);
    if (bGroup_ >= 0) glUniform1f(bGroup_, targetFbo_ != 0 ? 1.f : 0.f);
    glBindVertexArray(vao_);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glEnable(GL_BLEND);
    glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
}

void Renderer::drawLayerBlended(const LayerDraw& d, int mode, float opacity, const float* canvasRgb) {
    if (!ok_ || d.texture == 0 || d.contentW <= 0 || d.contentH <= 0) return;
    if (mode <= 0 || !ensureScratch()) {  // Normal, or blending unavailable: plain draw with opacity
        LayerDraw n = d;
        n.opacity = opacity;
        drawLayer(n);
        return;
    }
    // 1) the layer alone, on a transparent buffer (premultiplied, full strength)
    glBindFramebuffer(GL_FRAMEBUFFER, fboLayer_);
    glViewport(0, 0, w_, h_);
    glClearColor(0.f, 0.f, 0.f, 0.f);
    glClear(GL_COLOR_BUFFER_BIT);
    LayerDraw n = d;
    n.opacity = 1.f;
    drawLayer(n);
    // 2) blend it with the frame so far
    composeBlend(texLayer_, false, mode, opacity, canvasRgb);
}

void Renderer::blendTexture(uint32_t tex, int mode, float opacity, const float* canvasRgb) {
    if (!ok_ || tex == 0) return;
    if (!ensureScratch()) return;
    composeBlend(tex, true, mode, opacity, canvasRgb);
}

bool Renderer::layerLooksBlack(const LayerDraw& d) {
    if (!ok_ || d.texture == 0 || !ensureScratch()) return false;
    glBindFramebuffer(GL_FRAMEBUFFER, fboLayer_);
    glViewport(0, 0, w_, h_);
    glClearColor(0.f, 0.f, 0.f, 0.f);
    glClear(GL_COLOR_BUFFER_BIT);
    LayerDraw n = d;
    n.opacity = 1.f;
    drawLayer(n);
    std::vector<uint8_t> px((size_t)w_ * (size_t)h_ * 4);
    glPixelStorei(GL_PACK_ALIGNMENT, 1);
    glReadPixels(0, 0, w_, h_, GL_RGBA, GL_UNSIGNED_BYTE, px.data());
    glBindFramebuffer(GL_FRAMEBUFFER, targetFbo_);
    glViewport(0, 0, w_, h_);
    bool covered = false;
    for (size_t i = 0; i + 3 < px.size(); i += 16) {  // every 4th pixel
        if (px[i + 3] == 0) continue;                  // not covered by the layer
        covered = true;
        if (px[i] > 6 || px[i + 1] > 6 || px[i + 2] > 6) return false;
    }
    return covered;
}

void Renderer::releaseGroupTargets() {
    for (int i = 0; i < kGroupDepth; ++i) {
        if (grpFbo_[i]) glDeleteFramebuffers(1, &grpFbo_[i]);
        if (grpTex_[i]) glDeleteTextures(1, &grpTex_[i]);
        grpFbo_[i] = grpTex_[i] = 0;
    }
    grpW_ = grpH_ = 0;
    grpDepth_ = 0;
    targetFbo_ = 0;
}

// One canvas-sized RGBA8 target per nesting level; all of them are re-made when the output size changes.
bool Renderer::ensureGroupTargets() {
    if (w_ < 2 || h_ < 2) return false;
    if (grpW_ == w_ && grpH_ == h_ && grpFbo_[0]) return true;
    const int depth = grpDepth_;
    const uint32_t keep = targetFbo_;
    for (int i = 0; i < kGroupDepth; ++i) {
        if (grpFbo_[i]) glDeleteFramebuffers(1, &grpFbo_[i]);
        if (grpTex_[i]) glDeleteTextures(1, &grpTex_[i]);
        grpFbo_[i] = grpTex_[i] = 0;
    }
    grpW_ = grpH_ = 0;
    bool ok = true;
    for (int i = 0; i < kGroupDepth && ok; ++i) {
        glGenTextures(1, &grpTex_[i]);
        glBindTexture(GL_TEXTURE_2D, grpTex_[i]);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w_, h_, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glGenFramebuffers(1, &grpFbo_[i]);
        glBindFramebuffer(GL_FRAMEBUFFER, grpFbo_[i]);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, grpTex_[i], 0);
        ok = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
    }
    glBindFramebuffer(GL_FRAMEBUFFER, keep);
    if (!ok) {
        LOGE("group targets incomplete (%dx%d)", w_, h_);
        for (int i = 0; i < kGroupDepth; ++i) {
            if (grpFbo_[i]) glDeleteFramebuffers(1, &grpFbo_[i]);
            if (grpTex_[i]) glDeleteTextures(1, &grpTex_[i]);
            grpFbo_[i] = grpTex_[i] = 0;
        }
        grpDepth_ = depth;
        return false;
    }
    grpW_ = w_;
    grpH_ = h_;
    return true;
}

bool Renderer::beginGroup() {
    if (!ok_ || grpDepth_ >= kGroupDepth || !ensureGroupTargets()) return false;
    const uint32_t fbo = grpFbo_[grpDepth_];
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glViewport(0, 0, w_, h_);
    glScissor(0, 0, w_, h_);            // the group picture is canvas-sized: the frame clip stays on
    glEnable(GL_SCISSOR_TEST);
    glClearColor(0.f, 0.f, 0.f, 0.f);  // transparent: the group is its own picture, the canvas colour is not part of it
    glClear(GL_COLOR_BUFFER_BIT);
    glEnable(GL_BLEND);
    glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);  // premultiplied "over" also builds correct alpha in an RGBA target
    targetFbo_ = fbo;
    ++grpDepth_;
    return true;
}

uint32_t Renderer::endGroup() {
    if (!ok_ || grpDepth_ <= 0) return 0;
    --grpDepth_;
    const uint32_t tex = grpTex_[grpDepth_];
    targetFbo_ = grpDepth_ > 0 ? grpFbo_[grpDepth_ - 1] : 0;
    glBindFramebuffer(GL_FRAMEBUFFER, targetFbo_);
    glViewport(0, 0, w_, h_);
    return tex;
}

bool Renderer::readPixels(uint8_t* dst, int x, int y, int w, int h) {
    if (!ok_ || !dst) return false;
    glPixelStorei(GL_PACK_ALIGNMENT, 1);
    glReadPixels(x, y, w, h, GL_RGBA, GL_UNSIGNED_BYTE, dst);
    return glGetError() == GL_NO_ERROR;
}

bool Renderer::endFrame(int64_t ptsNs) {
    if (!ok_) return false;
    if (!outIsWindow_) {
        glFinish();  // offscreen / bootstrap: nothing to swap
        return true;
    }
    return egl_.swap(out_, ptsNs);
}

}  // namespace am
