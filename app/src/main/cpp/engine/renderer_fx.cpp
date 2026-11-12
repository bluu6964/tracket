// Cutout and face blur, done on the GPU inside the C++ engine (ports of the Media3 effects
// CutoutMaskEffect / FaceBlurEffect, same shaders and numbers, so the export looks the same).
#include <GLES3/gl3.h>
#include <android/log.h>

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <vector>

#include "jpeg_luma.h"
#include "renderer.h"

#define TAG "AmRendererFx"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace am {
namespace {

// Full-screen quad. vUv: (0,0) = bottom-left of the target. The picture textures made by applyFx()
// have the picture's TOP at v = 1 (the layer shader's convention); masks are bitmaps, row 0 = top,
// so they are read with v flipped.
const char* kFxVertex = R"(#version 300 es
layout(location = 0) in vec2 aPos;
out vec2 vUv;
void main() {
    gl_Position = vec4(aPos * 2.0, 0.0, 1.0);
    vUv = aPos + 0.5;
}
)";

// 4 x 4 taps over the area one small-picture pixel covers: a proper reduction, no aliasing.
const char* kFxDown = R"(#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform vec2 uStep;
in vec2 vUv;
out vec4 oColor;
void main() {
    vec4 sum = vec4(0.0);
    for (int j = 0; j < 4; j++) {
        for (int i = 0; i < 4; i++) {
            vec2 o = vec2(float(i) - 1.5, float(j) - 1.5) * uStep;
            sum += texture(uTex, vUv + o);
        }
    }
    oColor = sum / 16.0;
}
)";

// 13-tap Gaussian (sigma = 3 taps) along uOffset.
const char* kFxBlur = R"(#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform vec2 uOffset;
in vec2 vUv;
out vec4 oColor;
void main() {
    vec4 sum = vec4(0.0);
    float wsum = 0.0;
    for (int i = -6; i <= 6; i++) {
        float fi = float(i);
        float w = exp(-fi * fi / 18.0);
        sum += texture(uTex, vUv + uOffset * fi) * w;
        wsum += w;
    }
    oColor = sum / wsum;
}
)";

// Vertical blur of the small picture, mixed over the sharp frame through the mask.
const char* kFxFace = R"(#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform sampler2D uBlur;
uniform sampler2D uMask;
uniform vec2 uOffset;
in vec2 vUv;
out vec4 oColor;
void main() {
    vec4 c = texture(uTex, vUv);
    vec4 sum = vec4(0.0);
    float wsum = 0.0;
    for (int i = -6; i <= 6; i++) {
        float fi = float(i);
        float w = exp(-fi * fi / 18.0);
        sum += texture(uBlur, vUv + uOffset * fi) * w;
        wsum += w;
    }
    vec4 b = sum / wsum;
    float m = texture(uMask, vec2(vUv.x, 1.0 - vUv.y)).r;
    m = clamp(m * 1.1, 0.0, 1.0);
    oColor = vec4(mix(c.rgb, b.rgb, m), c.a);
}
)";

// Cutout: the person (mask = white) stays. Flatten = drawn straight onto the canvas colour.
// The picture is premultiplied, so the kept colour is scaled by the mask as well.
const char* kFxCut = R"(#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform sampler2D uMask;
uniform float uFlatten;
uniform vec3 uCanvas;
in vec2 vUv;
out vec4 oColor;
void main() {
    vec4 c = texture(uTex, vUv);
    float a = texture(uMask, vec2(vUv.x, 1.0 - vUv.y)).r;
    if (uFlatten > 0.5) {
        oColor = vec4(mix(uCanvas, c.rgb, a), 1.0);
    } else {
        oColor = vec4(c.rgb * a, c.a * a);
    }
}
)";

// Layer mask over a ready picture (a group layer's composed picture, cut by the Alight mask layer
// sitting on it): the picture keeps only where the mask layer is (DST_IN). An Exclude Mask layer
// (uSubtract = 1, build 138) subtracts instead: the picture keeps only where the mask layer is NOT
// (DST_OUT — the same one-minus-coverage rule am::layerMaskCut pins on the CPU side). The mask is
// the mask layer drawn by the Kotlin painters into a Bitmap and uploaded: a bitmap texture,
// row 0 = top, read with v flipped, and its ALPHA is the coverage (the same rule the CPU overlay
// path applies with a PorterDuff DST_IN / DST_OUT paint). The picture is premultiplied, so its
// colour scales along.
const char* kFxTexMask = R"(#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform sampler2D uMask;
uniform float uSubtract;
in vec2 vUv;
out vec4 oColor;
void main() {
    vec4 c = texture(uTex, vUv);
    float a = texture(uMask, vec2(vUv.x, 1.0 - vUv.y)).a;
    float keep = uSubtract > 0.5 ? 1.0 - a : a;
    oColor = vec4(c.rgb * keep, c.a * keep);
}
)";

uint32_t compileShader(GLenum type, const char* src) {
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

uint32_t makeProgram(const char* fragment) {
    const uint32_t vs = compileShader(GL_VERTEX_SHADER, kFxVertex);
    const uint32_t fs = compileShader(GL_FRAGMENT_SHADER, fragment);
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
        char log[512] = {0};
        glGetProgramInfoLog(prog, sizeof(log) - 1, nullptr, log);
        LOGE("program link failed: %s", log);
        glDeleteProgram(prog);
        return 0;
    }
    return prog;
}

bool makeTarget(int w, int h, uint32_t* tex, uint32_t* fbo) {
    glGenTextures(1, tex);
    glBindTexture(GL_TEXTURE_2D, *tex);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
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

bool readFile(const char* path, std::vector<uint8_t>* out) {
    FILE* f = fopen(path, "rb");
    if (!f) return false;
    uint8_t buf[8192];
    size_t n;
    while ((n = fread(buf, 1, sizeof(buf), f)) > 0) out->insert(out->end(), buf, buf + n);
    fclose(f);
    return !out->empty();
}

}  // namespace

uint32_t Renderer::loadMask(int slot, const char* path) {
    if (!ok_ || !path) return 0;
    MaskSlot& s = masks_[slot];
    if (s.tex && s.path == path) return s.tex;  // neighbouring frames share one mask picture
    std::vector<uint8_t> file, y;
    int w = 0, h = 0;
    if (!readFile(path, &file) || !decodeJpegLuma(file.data(), file.size(), &y, &w, &h)) {
        LOGE("mask not readable: %s", path);
        return 0;
    }
    if (!s.tex) s.tex = createTexture(false);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, s.tex);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, w, h, 0, GL_RED, GL_UNSIGNED_BYTE, y.data());
    s.path = path;
    return s.tex;
}

bool Renderer::buildFxPrograms() {
    if (fxDown_ && fxBlur_ && fxFace_ && fxCut_) return true;
    fxDown_ = makeProgram(kFxDown);
    fxBlur_ = makeProgram(kFxBlur);
    fxFace_ = makeProgram(kFxFace);
    fxCut_ = makeProgram(kFxCut);
    if (!(fxDown_ && fxBlur_ && fxFace_ && fxCut_)) {
        if (fxDown_) glDeleteProgram(fxDown_);
        if (fxBlur_) glDeleteProgram(fxBlur_);
        if (fxFace_) glDeleteProgram(fxFace_);
        if (fxCut_) glDeleteProgram(fxCut_);
        fxDown_ = fxBlur_ = fxFace_ = fxCut_ = 0;
        return false;
    }
    return true;
}

bool Renderer::ensureFxTargets(int w, int h, int lw, int lh) {
    if (fxTex_[0] && fxW_ == w && fxH_ == h && lowW_ == lw && lowH_ == lh) return true;
    for (int i = 0; i < 2; ++i) {
        dropTarget(&fxTex_[i], &fxFbo_[i]);
        dropTarget(&lowTex_[i], &lowFbo_[i]);
    }
    fxW_ = fxH_ = lowW_ = lowH_ = 0;
    bool ok = true;
    for (int i = 0; i < 2 && ok; ++i) ok = makeTarget(w, h, &fxTex_[i], &fxFbo_[i]);
    for (int i = 0; i < 2 && ok && lw > 0; ++i) ok = makeTarget(lw, lh, &lowTex_[i], &lowFbo_[i]);
    glBindFramebuffer(GL_FRAMEBUFFER, targetFbo_);  // back to the frame (a group's target inside a group pass)
    if (!ok) {
        LOGE("fx targets incomplete (%dx%d, %dx%d)", w, h, lw, lh);
        for (int i = 0; i < 2; ++i) {
            dropTarget(&fxTex_[i], &fxFbo_[i]);
            dropTarget(&lowTex_[i], &lowFbo_[i]);
        }
        return false;
    }
    fxW_ = w;
    fxH_ = h;
    lowW_ = lw;
    lowH_ = lh;
    return true;
}

void Renderer::releaseFx() {
    for (int i = 0; i < 2; ++i) {
        dropTarget(&fxTex_[i], &fxFbo_[i]);
        dropTarget(&lowTex_[i], &lowFbo_[i]);
    }
    fxW_ = fxH_ = lowW_ = lowH_ = 0;
    if (fxLayerMask_) glDeleteProgram(fxLayerMask_);
    fxLayerMask_ = 0;
    if (fxDown_) glDeleteProgram(fxDown_);
    if (fxBlur_) glDeleteProgram(fxBlur_);
    if (fxFace_) glDeleteProgram(fxFace_);
    if (fxCut_) glDeleteProgram(fxCut_);
    fxDown_ = fxBlur_ = fxFace_ = fxCut_ = 0;
    for (auto& kv : masks_) {
        if (kv.second.tex) glDeleteTextures(1, &kv.second.tex);
    }
    masks_.clear();
    releaseEffects();
}

bool Renderer::applyFx(const LayerDraw& d, const LayerFx& fx, LayerDraw* out) {
    if (!out) return false;
    *out = d;
    if (!ok_ || d.texture == 0 || d.contentW <= 0 || d.contentH <= 0) return false;
    if (!fx.faceMask && !fx.cutMask && fx.nOps <= 0 && fx.nMasks <= 0) return false;  // nothing to do: the caller draws the source itself
    if ((fx.faceMask || fx.cutMask) && !buildFxPrograms()) return false;

    // A crop of the picture (fractions, origin top-left) makes the effects run on that part only, at its own pixel size:
    // text / shape layers sit small inside a canvas-sized picture, so vignette, mirror, mosaic ... must see the layer's box.
    const float ccw = std::fmin(1.f, std::fmax(0.01f, d.cropW));
    const float cch = std::fmin(1.f, std::fmax(0.01f, d.cropH));
    const int W = std::max(1, (int)std::lround((double)d.contentW * ccw));
    const int H = std::max(1, (int)std::lround((double)d.contentH * cch));
    int lw = 0, lh = 0;
    if (fx.faceMask) {
        const float s = std::fmin(1.f, std::fmax(0.f, fx.faceStrength));
        lw = (int)std::lround(224.f + (96.f - 224.f) * s);
        lw = std::max(16, std::min(lw, W));
        lh = std::max(16, (int)std::lround((double)lw * H / (double)W));
    }
    if (!ensureFxTargets(W, H, lw, lh)) return false;

    // The frame clip (the canvas scissor beginFrame keeps on) must not cut the effects' own
    // rendering: the targets here are the size of the layer's picture, which can be larger than
    // the canvas. Saved here, switched off for the whole call, restored with the framebuffer /
    // viewport at the end.
    const GLboolean scissorWas = glIsEnabled(GL_SCISSOR_TEST);
    GLint scissorBox[4] = {0, 0, 0, 0};
    if (scissorWas) glGetIntegerv(GL_SCISSOR_BOX, scissorBox);
    glDisable(GL_SCISSOR_TEST);

    // Copy Background: the frame so far goes into the backdrop texture before anything is drawn into the effect targets.
    bool needBg = false;
    for (int k = 0; k < fx.nOps; ++k)
        if ((fx.ops[k].type == FX_COPY_BG || fx.ops[k].type == FX_DISPLACE_MAP_AM ||
             fx.ops[k].type == FX_DISPLACE_MAP_RAD_AM) && !fxIsIdentity(fx.ops[k])) needBg = true;
    const bool haveScratch = needBg && fx.bgAligned && ensureScratch();  // may rebind the default framebuffer when it creates the scratch

    GLint prevFbo = 0;
    GLint vp[4] = {0, 0, w_, h_};
    glGetIntegerv(GL_FRAMEBUFFER_BINDING, &prevFbo);
    glGetIntegerv(GL_VIEWPORT, vp);
    bgOk_ = false;
    if (haveScratch) {
        glBindFramebuffer(GL_FRAMEBUFFER, targetFbo_);  // the frame target (same as composeBlend)
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, texBackdrop_);
        glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 0, 0, w_, h_);
        glActiveTexture(GL_TEXTURE0);
        glBindFramebuffer(GL_FRAMEBUFFER, (GLuint)prevFbo);
        const float bx = std::fmin(1.f - ccw, std::fmax(0.f, d.cropX));
        const float by = std::fmin(1.f - cch, std::fmax(0.f, d.cropY));
        bgRect_[0] = bx;
        bgRect_[1] = 1.f - by - cch;  // from the bottom
        bgRect_[2] = ccw;
        bgRect_[3] = cch;
        bgOk_ = true;
    }

    // 0) the source picture, upright and at its own size, into a target — never the target the
    // source itself lives in (fxFirstTarget: a chained pass whose input is a previous result
    // would otherwise clear and sample the same texture, erasing the picture).
    const int start = fxFirstTarget(d.texture, fxTex_[0]);
    glBindFramebuffer(GL_FRAMEBUFFER, fxFbo_[start]);
    glViewport(0, 0, W, H);
    glClearColor(0.f, 0.f, 0.f, 0.f);
    glClear(GL_COLOR_BUFFER_BIT);
    {
        const int sw = w_, sh = h_;
        w_ = W;  // drawLayer fits the picture into the canvas: here canvas == picture, scale 1
        h_ = H;
        LayerDraw n = d;
        n.offsetX = n.offsetY = 0.f;
        n.scaleX = n.scaleY = 1.f;
        n.rotationDeg = 0.f;
        n.opacity = 1.f;
        n.cropX = d.cropX;  // the part of the picture the effects run on (whole picture = 0, 0, 1, 1)
        n.cropY = d.cropY;
        n.cropW = d.cropW;
        n.cropH = d.cropH;
        glEnable(GL_BLEND);
        glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        drawLayer(n);
        w_ = sw;
        h_ = sh;
    }

    glDisable(GL_BLEND);
    glBindVertexArray(vao_);
    int cur = start;  // index of the target that holds the current picture

    if (fx.faceMask) {
        // 1) box-reduce the frame to the small picture
        glBindFramebuffer(GL_FRAMEBUFFER, lowFbo_[0]);
        glViewport(0, 0, lw, lh);
        glUseProgram(fxDown_);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, fxTex_[cur]);
        glUniform1i(glGetUniformLocation(fxDown_, "uTex"), 0);
        glUniform2f(glGetUniformLocation(fxDown_, "uStep"), 1.f / (4.f * lw), 1.f / (4.f * lh));
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

        // 2) horizontal blur
        glBindFramebuffer(GL_FRAMEBUFFER, lowFbo_[1]);
        glViewport(0, 0, lw, lh);
        glUseProgram(fxBlur_);
        glBindTexture(GL_TEXTURE_2D, lowTex_[0]);
        glUniform1i(glGetUniformLocation(fxBlur_, "uTex"), 0);
        glUniform2f(glGetUniformLocation(fxBlur_, "uOffset"), 1.3f / lw, 0.f);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

        // 3) vertical blur + mix over the sharp picture through the mask -> the other target
        const int dst = 1 - cur;
        glBindFramebuffer(GL_FRAMEBUFFER, fxFbo_[dst]);
        glViewport(0, 0, W, H);
        glUseProgram(fxFace_);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, fxTex_[cur]);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, lowTex_[1]);
        glActiveTexture(GL_TEXTURE2);
        glBindTexture(GL_TEXTURE_2D, fx.faceMask);
        glUniform1i(glGetUniformLocation(fxFace_, "uTex"), 0);
        glUniform1i(glGetUniformLocation(fxFace_, "uBlur"), 1);
        glUniform1i(glGetUniformLocation(fxFace_, "uMask"), 2);
        glUniform2f(glGetUniformLocation(fxFace_, "uOffset"), 0.f, 1.3f / lh);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        cur = dst;
    }

    if (fx.cutMask) {
        const int dst = 1 - cur;
        glBindFramebuffer(GL_FRAMEBUFFER, fxFbo_[dst]);
        glViewport(0, 0, W, H);
        glUseProgram(fxCut_);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, fxTex_[cur]);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, fx.cutMask);
        glUniform1i(glGetUniformLocation(fxCut_, "uTex"), 0);
        glUniform1i(glGetUniformLocation(fxCut_, "uMask"), 1);
        glUniform1f(glGetUniformLocation(fxCut_, "uFlatten"), fx.flatten ? 1.f : 0.f);
        glUniform3f(glGetUniformLocation(fxCut_, "uCanvas"), fx.canvasRgb[0], fx.canvasRgb[1], fx.canvasRgb[2]);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        cur = dst;
    }

    if (fx.nOps > 0) runEffects(fx, W, H, &cur);
    if (fx.nMasks > 0 && !runMasks(fx, W, H, &cur)) LOGE("masks skipped (shader or target failed)");

    // back to what the caller had
    glActiveTexture(GL_TEXTURE0);
    glBindFramebuffer(GL_FRAMEBUFFER, (GLuint)prevFbo);
    glViewport(vp[0], vp[1], vp[2], vp[3]);
    if (scissorWas) {
        glScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3]);
        glEnable(GL_SCISSOR_TEST);
    }
    glEnable(GL_BLEND);
    glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);

    out->texture = fxTex_[cur];
    out->oes = false;
    for (int i = 0; i < 16; ++i) out->texMatrix[i] = (i % 5 == 0) ? 1.f : 0.f;  // identity
    out->contentRotation = 0;  // the picture is already upright
    return true;
}

bool Renderer::applyTexMask(const LayerDraw& d, uint32_t maskTex, bool subtract, LayerDraw* out) {
    if (!out) return false;
    *out = d;
    if (!ok_ || d.texture == 0 || maskTex == 0 || d.contentW <= 0 || d.contentH <= 0) return false;
    if (!fxLayerMask_) fxLayerMask_ = makeProgram(kFxTexMask);
    if (!fxLayerMask_) return false;

    // Same picture geometry as applyFx (a crop runs the pass on that part of the picture only).
    const float ccw = std::fmin(1.f, std::fmax(0.01f, d.cropW));
    const float cch = std::fmin(1.f, std::fmax(0.01f, d.cropH));
    const int W = std::max(1, (int)std::lround((double)d.contentW * ccw));
    const int H = std::max(1, (int)std::lround((double)d.contentH * cch));
    if (!ensureFxTargets(W, H, 0, 0)) return false;

    // The frame clip must not cut this pass (see applyFx): saved, off for the call, restored at the end.
    const GLboolean scissorWas = glIsEnabled(GL_SCISSOR_TEST);
    GLint scissorBox[4] = {0, 0, 0, 0};
    if (scissorWas) glGetIntegerv(GL_SCISSOR_BOX, scissorBox);
    glDisable(GL_SCISSOR_TEST);

    GLint prevFbo = 0;
    GLint vp[4] = {0, 0, w_, h_};
    glGetIntegerv(GL_FRAMEBUFFER_BINDING, &prevFbo);
    glGetIntegerv(GL_VIEWPORT, vp);

    // 0) the source picture, upright and at its own size, into a target (same copy as applyFx) —
    // never the target the source itself lives in (fxFirstTarget: the group path hands this
    // pass a post-effects picture, which can be fx target 0).
    const int start = fxFirstTarget(d.texture, fxTex_[0]);
    const int dst = 1 - start;
    glBindFramebuffer(GL_FRAMEBUFFER, fxFbo_[start]);
    glViewport(0, 0, W, H);
    glClearColor(0.f, 0.f, 0.f, 0.f);
    glClear(GL_COLOR_BUFFER_BIT);
    {
        const int sw = w_, sh = h_;
        w_ = W;  // drawLayer fits the picture into the canvas: here canvas == picture, scale 1
        h_ = H;
        LayerDraw n = d;
        n.offsetX = n.offsetY = 0.f;
        n.scaleX = n.scaleY = 1.f;
        n.rotationDeg = 0.f;
        n.opacity = 1.f;
        n.cropX = d.cropX;
        n.cropY = d.cropY;
        n.cropW = d.cropW;
        n.cropH = d.cropH;
        glEnable(GL_BLEND);
        glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        drawLayer(n);
        w_ = sw;
        h_ = sh;
    }

    // 1) the mask multiply into the other target: the picture keeps only where the mask layer is
    // (an exclusion cut keeps it where the mask layer is NOT — see kFxTexMask).
    glDisable(GL_BLEND);
    glBindVertexArray(vao_);
    glBindFramebuffer(GL_FRAMEBUFFER, fxFbo_[dst]);
    glViewport(0, 0, W, H);
    glUseProgram(fxLayerMask_);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, fxTex_[start]);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, maskTex);
    glUniform1i(glGetUniformLocation(fxLayerMask_, "uTex"), 0);
    glUniform1i(glGetUniformLocation(fxLayerMask_, "uMask"), 1);
    glUniform1f(glGetUniformLocation(fxLayerMask_, "uSubtract"), subtract ? 1.f : 0.f);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

    // back to what the caller had
    glActiveTexture(GL_TEXTURE0);
    glBindFramebuffer(GL_FRAMEBUFFER, (GLuint)prevFbo);
    glViewport(vp[0], vp[1], vp[2], vp[3]);
    if (scissorWas) {
        glScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3]);
        glEnable(GL_SCISSOR_TEST);
    }
    glEnable(GL_BLEND);
    glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);

    out->texture = fxTex_[dst];
    out->oes = false;
    for (int i = 0; i < 16; ++i) out->texMatrix[i] = (i % 5 == 0) ? 1.f : 0.f;  // identity
    out->contentRotation = 0;  // the picture is already upright
    return true;
}

}  // namespace am
