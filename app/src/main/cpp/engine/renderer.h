// OpenGL ES 3 layer renderer. The SAME code draws the editor preview (output = SurfaceView /
// TextureView surface) and the export (output = MediaCodec encoder input surface).
// All methods must be called from the one thread that created the Renderer.
#pragma once
#include <cstdint>
#include <string>
#include <unordered_map>

#include "egl_core.h"
#include "fx_plan.h"
#include "mask_plan.h"
#include "gl_math.h"

struct ANativeWindow;

namespace am {

struct LayerDraw {
    uint32_t texture = 0;
    bool oes = true;                 // GL_TEXTURE_EXTERNAL_OES (decoder output) or plain 2D texture
    float texMatrix[16] = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};  // SurfaceTexture matrix
    int contentW = 1, contentH = 1;  // picture size AFTER applying contentRotation
    int contentRotation = 0;         // video rotation metadata, degrees clockwise (0/90/180/270)
    float offsetX = 0, offsetY = 0;  // canvas pixels from the centre
    float scaleX = 1, scaleY = 1;    // 1 = fitted ("contain") into the canvas
    float rotationDeg = 0;           // clockwise
    float opacity = 1;
    // Visible part of the (upright) picture, fractions 0..1, origin top-left. The cropped part is what
    // gets fitted into the canvas; contentW / contentH stay the size of the WHOLE picture.
    float cropX = 0, cropY = 0, cropW = 1, cropH = 1;
    // 3D turn around the pivot with perspective (v121). Used only when perspD > 0 and rotX / rotY are not both 0;
    // then offsetX / offsetY are ignored and baseX / baseY (position after depth, no pivot compensation) are used.
    float rotX = 0, rotY = 0;          // degrees, Compose convention (see gl_math.h)
    float pivotX = 0, pivotY = 0;      // pivot relative to the canvas centre, canvas pixels
    float baseX = 0, baseY = 0;        // canvas pixels from the centre
    float perspD = 0;                  // camera distance, canvas pixels (0 = flat)
    bool is3d() const { return perspD > 0.f && (rotX != 0.f || rotY != 0.f); }
    // Corner pin (v142): when [pin] is true the picture is drawn on the quad [pinQ] (canvas pixels from the TOP-LEFT of the
    // canvas: TL, TR, BR, BL; x, y pairs) with perspective, and offset / scale / rotation / 3D are ignored.
    bool pin = false;
    float pinQ[8] = {0, 0, 0, 0, 0, 0, 0, 0};
    // Brightness factor of the colour (v143, Remove: the patch follows the light of the video). 1 = as is. Plain 2D pictures only.
    float gain = 1.f;
};

// The model matrix drawLayer uses for [d] on a canvas of w x h pixels (before Mat4::canvas, z dropped when 3D). The same
// function maps tracked picture points to canvas pixels for the corner pin, so the pinned layer lands exactly on the surface.
Mat4 layerModelFor(const LayerDraw& d, int w, int h);

// Effects that run on the decoded picture BEFORE it is placed on the canvas (same maths as the old
// Media3 CutoutMaskEffect / FaceBlurEffect). Mask textures come from Renderer::loadMask().
struct LayerFx {
    uint32_t faceMask = 0;      // 0 = no face blur
    float faceStrength = 0.f;   // 0..1 (FaceBlurLook: 0 -> 224 px blur picture, 1 -> 96 px)
    uint32_t cutMask = 0;       // 0 = no cutout
    bool flatten = false;       // lowest layer: the person is drawn straight onto the canvas colour
    float canvasRgb[3] = {0, 0, 0};
    // The picture sits at the same place as on the canvas (text / shape / sticker pictures): Copy Background may read the backdrop.
    bool bgAligned = false;
    // The effects of the layer (Effects panel), run in list order AFTER face blur / cutout.
    FxOp ops[kFxMaxOps];
    int nOps = 0;
    int grainFrame = 0;         // film grain pattern number (fxGrainFrame(timeline ms)): preview == export
    // The masks of the layer (Masks panel), run LAST, after the effects: the layer times the combined mask coverage.
    MaskOp masks[kMaskMax];
    int nMasks = 0;
};

class Renderer {
public:
    ~Renderer() { release(); }

    bool init();
    void release();

    uint32_t createTexture(bool oes);
    void deleteTexture(uint32_t tex);
    // Fills a plain 2D texture with w * h RGBA8 pixels (rows bottom-to-top, like readPixels returns them).
    bool uploadRgba(uint32_t tex, int w, int h, const uint8_t* rgba);

    // Output = window (preview surface or encoder input surface). null = back to the 1x1 bootstrap.
    bool setOutputWindow(ANativeWindow* window);
    bool setOutputOffscreen(int w, int h);  // needs pbuffer support (checked at init)
    int width() const { return w_; }
    int height() const { return h_; }

    // Starts a frame on the current output. The frame is clipped to the canvas (0, 0, w_, h_): a
    // scissor on that rect stays enabled for the whole composition (layers, blends, group targets),
    // so nothing a layer draws lands outside the canvas. applyFx turns it off only while rendering
    // into its picture-sized targets and restores it before returning.
    void beginFrame(float r, float g, float b, float a);
    void drawLayer(const LayerDraw& d);
    // Blend modes (ids = BlendModes.kt shaderId: 0 Normal, 1-4 mattes, 10.. colour blends). The layer is
    // drawn alone into a scratch buffer and then combined with what is already on the frame (the
    // "backdrop"). opacity is applied by the blend shader. canvasRgb (3 floats) = canvas colour (mattes).
    void drawLayerBlended(const LayerDraw& d, int mode, float opacity, const float* canvasRgb);
    // Same for a ready 2D picture the size of the canvas (premultiplied RGBA, first row = TOP, e.g. a Bitmap).
    void blendTexture(uint32_t tex, int mode, float opacity, const float* canvasRgb);
    // Diagnostics: draws the layer alone (scratch buffer) and tells whether everything it covers is pure
    // black. A decoder texture that has no usable picture samples as opaque black.
    bool layerLooksBlack(const LayerDraw& d);
    // Mask pictures (saved as small grey JPEGs by the cutout / face blur tools). `slot` is any id the
    // caller picks per mask sequence: the texture of a slot is re-used while the path is unchanged and
    // overwritten when it changes. Returns an R8 texture (row 0 = top), or 0 when the file cannot be
    // read / decoded (e.g. a progressive JPEG).
    uint32_t loadMask(int slot, const char* path);
    // Runs face blur and / or cutout on the picture of `src` and returns, in `out`, the same layer
    // pointing at an upright 2D texture (texMatrix = identity, contentRotation = 0). The texture is
    // owned by the renderer and valid until the next applyFx() call. Crop / transform stay in `out`.
    bool applyFx(const LayerDraw& src, const LayerFx& fx, LayerDraw* out);
    // Layer mask over a ready picture (an Alight `blending="mask"` layer cutting a GROUP layer's
    // composed picture): multiplies the picture by the mask texture's alpha (DST_IN — the picture
    // keeps only where the mask is). With `subtract` (an Alight Exclude Mask layer, build 138) it
    // multiplies by one-minus-alpha instead (DST_OUT — the picture is removed where the mask is).
    // `maskTex` is a plain 2D texture whose first row is the TOP of
    // the canvas (e.g. an uploaded Bitmap of the mask layer drawn by the Kotlin painters); the
    // picture itself is a renderer texture (rows bottom-to-top), the shader flips the mask's v.
    // Same ownership as applyFx: the result lives in the fx targets, valid until the next
    // applyFx / applyTexMask call. False = nothing was done (the caller draws the source itself).
    bool applyTexMask(const LayerDraw& src, uint32_t maskTex, bool subtract, LayerDraw* out);
    // Group pass: a Group layer is drawn as ONE picture. beginGroup() makes the frame target a transparent canvas-sized
    // picture (premultiplied RGBA); everything drawn until endGroup() lands in it, blend modes of the layers inside blend
    // with the group's own contents only. endGroup() puts the previous target back (the frame, or the group this one is
    // inside, up to kGroupDepth levels) and returns the group's picture (rows bottom-to-top = a plain 2D texture: draw it
    // with an identity texture matrix, contentW / contentH = the canvas size, no offset / scale). The texture belongs to the
    // renderer and stays valid until the next beginGroup() at the same depth. beginGroup() returns false when it cannot
    // (too deep / no memory): then draw the layers of the group straight into the frame instead.
    bool beginGroup();
    uint32_t endGroup();
    // Debug / thumbnails: RGBA, rows bottom-to-top. Call before endFrame().
    bool readPixels(uint8_t* dst, int x, int y, int w, int h);
    // ptsNs >= 0 stamps the frame for the encoder. Offscreen outputs just flush.
    bool endFrame(int64_t ptsNs);

private:
    bool buildPrograms();
    bool buildBlendProgram();
    bool ensureScratch();
    bool ensureGroupTargets();
    void releaseGroupTargets();
    void composeBlend(uint32_t layerTex, bool flipV, int mode, float opacity, const float* canvasRgb);
    void useOutput(EGLSurface s, bool window);
    void dropOutput();
    bool buildFxPrograms();
    // renderer_effects.cpp: the user effects. `cur` = index of the picture-sized target that holds the picture.
    void runEffects(const LayerFx& fx, int W, int H, int* cur);
    bool runMasks(const LayerFx& fx, int W, int H, int* cur);
    bool ensureMaskTargets(int w, int h);
    bool fxBlur(const FxOp& op, int W, int H, int* cur);
    bool fxBlurPx(float sigmaPx, int W, int H, int* cur);
    bool fxGlow(const FxOp& op, int W, int H, int* cur);
    bool fxSplit(const FxOp& op, int W, int H, int* cur);
    bool fxGrain(const FxOp& op, int W, int H, int* cur, int frame);
    bool fxVignette(const FxOp& op, int W, int H, int* cur);
    bool fxChroma(const FxOp& op, int W, int H, int* cur);
    bool fxCopyBg(const FxOp& op, int W, int H, int* cur);
    bool fxGlowScan(const FxOp& op, int W, int H, int* cur);
    // v163: dedicated passes of Alight's next tier (they need the extended params q, or scale alpha,
    // so they cannot ride the shared fxShaded programs).
    bool fxLumaKey(const FxOp& op, int W, int H, int* cur);
    bool fxFourColorGradient(const FxOp& op, int W, int H, int* cur);
    bool fxTurbulentAm(const FxOp& op, int W, int H, int* cur);
    bool fxPixelateAm(const FxOp& op, int W, int H, int* cur);
    bool fxChromaKeyAm(const FxOp& op, int W, int H, int* cur);
    bool fxSoftGlowAm(const FxOp& op, int W, int H, int* cur);
    bool fxFillBehindAm(const FxOp& op, int W, int H, int* cur);
    bool fxDarkGlowAm(const FxOp& op, int W, int H, int* cur);
    bool fxEdgeGlowAm(const FxOp& op, int W, int H, int* cur);
    // Tier 3 dedicated passes: Displace Map (samples texBackdrop_ like fxCopyBg), Smooth
    // Bevel (two passes through an lv_ distance buffer), Light Glow (four passes through
    // half-size lv_ buffers).
    bool fxDisplaceMapAm(const FxOp& op, int W, int H, int* cur);
    bool fxSmoothBevelAm(const FxOp& op, int W, int H, int* cur);
    bool fxLightGlowAm(const FxOp& op, int W, int H, int* cur);
    bool fxInnerGlowAm(const FxOp& op, int W, int H, int* cur);
    bool fxExtrudeAm(const FxOp& op, int W, int H, int* cur);
    // Tier 4 dedicated passes (v175): Channel Map (one pass), Mosaic (two passes),
    // Box Blur 3 / Inner Blur / Feather (the shared box-pair program), Glow (the
    // outer-glow distance scan), Electric Edges (blur buffer + main pass), Smooth
    // Edges (blur copy + streak composites).
    bool fxChannelMapAm(const FxOp& op, int W, int H, int* cur);
    bool fxMosaicAm(const FxOp& op, int W, int H, int* cur);
    bool fxBoxBlur3Am(const FxOp& op, int W, int H, int* cur);
    bool fxInnerBlurAm(const FxOp& op, int W, int H, int* cur);
    bool fxFeatherAm(const FxOp& op, int W, int H, int* cur);
    bool fxGlowOuterAm(const FxOp& op, int W, int H, int* cur);
    bool fxElectricEdgesAm(const FxOp& op, int W, int H, int* cur);
    bool fxSmoothEdgesAm(const FxOp& op, int W, int H, int* cur);
    bool fxGaussianBlurAm(const FxOp& op, int W, int H, int* cur);
    bool fxDeepGlow2Am(const FxOp& op, int W, int H, int* cur);
    bool fxDropShadowAm(const FxOp& op, int W, int H, int* cur);
    bool fxBbMakerAm(const FxOp& op, int W, int H, int* cur);
    bool fxOpticCompAm(const FxOp& op, int W, int H, int* cur);
    bool fxUnmultAm(const FxOp& op, int W, int H, int* cur);
    bool fxMinimaxAm(const FxOp& op, int W, int H, int* cur);
    bool fxSoftbloomAm(const FxOp& op, int W, int H, int* cur);
    bool fxLensdAm(const FxOp& op, int W, int H, int* cur);
    bool fxUnsharpMaskAm(const FxOp& op, int W, int H, int* cur);
    bool fxNewSharpenAm(const FxOp& op, int W, int H, int* cur);
    bool fxAmBlendGenAm(const FxOp& op, int W, int H, int* cur);
    bool fxDeepGlow1Am(const FxOp& op, int W, int H, int* cur);
    bool fxSpillCleanupAm(const FxOp& op, int W, int H, int* cur);
    // Copy Background: the frame so far is copied into texBackdrop_ at the start of applyFx; bgRect_ = the part of the canvas
    // the picture covers (x, y from the BOTTOM, w, h as fractions), bgOk_ = the copy was made for this call.
    bool bgOk_ = false;
    float bgRect_[4] = {0.f, 0.f, 1.f, 1.f};
    // v140: every effect from FX_DIR_BLUR on, through one of the four shared programs (fx_shaders.h).
    bool fxShaded(const FxOp& op, int W, int H, int* cur, int frame);
    uint32_t effProg(int id);
    bool ensureLevel(int i, int w, int h);
    void releaseEffects();
    bool ensureFxTargets(int w, int h, int lw, int lh);
    void releaseFx();

    struct Program {
        uint32_t id = 0;
        int mvp = -1, tex = -1, sampler = -1, opacity = -1, gain = -1;
    };
    EglCore egl_;
    Program oes_, flat_;
    // Blend pass: scratch buffers are canvas-sized (re-created when the output size changes).
    uint32_t blendProg_ = 0;
    int bBackdrop_ = -1, bLayer_ = -1, bMode_ = -1, bOpacity_ = -1, bCanvas_ = -1, bFlip_ = -1;
    uint32_t fboLayer_ = 0, texLayer_ = 0, texBackdrop_ = 0;
    int scratchW_ = 0, scratchH_ = 0;
    int bGroup_ = -1;  // blend shader: 1 = the frame target is a group picture (its backdrop can be transparent)
    // Group pass (beginGroup / endGroup): one canvas-sized target per nesting level. targetFbo_ = where the frame is drawn
    // now (0 = the output surface, else the target of the group being drawn): every "back to the frame" uses it.
    static constexpr int kGroupDepth = 4;
    uint32_t grpFbo_[kGroupDepth] = {0, 0, 0, 0};
    uint32_t grpTex_[kGroupDepth] = {0, 0, 0, 0};
    int grpW_ = 0, grpH_ = 0;
    int grpDepth_ = 0;
    uint32_t targetFbo_ = 0;
    // Effects (renderer_fx.cpp)
    uint32_t fxDown_ = 0, fxBlur_ = 0, fxFace_ = 0, fxCut_ = 0;
    uint32_t fxLayerMask_ = 0;  // applyTexMask's program (built lazily, released with the fx programs)
    uint32_t fxTex_[2] = {0, 0}, fxFbo_[2] = {0, 0};      // picture-sized ping-pong targets
    uint32_t lowTex_[2] = {0, 0}, lowFbo_[2] = {0, 0};    // small pictures for the blur
    int fxW_ = 0, fxH_ = 0, lowW_ = 0, lowH_ = 0;
    struct MaskSlot {
        std::string path;
        uint32_t tex = 0;
    };
    std::unordered_map<int, MaskSlot> masks_;
    // Effect programs (built when first used) and the small pictures of the blur / glow pyramids.
    uint32_t eff_[56] = {0};
    uint32_t maskTex_[2] = {0, 0}, maskFbo_[2] = {0, 0};  // R8 coverage pictures (ping-pong)
    int maskW_ = 0, maskH_ = 0;
    struct FxLevel {
        uint32_t tex[2] = {0, 0}, fbo[2] = {0, 0};
        int w = 0, h = 0;
    };
    FxLevel lv_[kFxGlowLevels];
    int lvHalf_ = -1;  // -1 = not tested, 1 = pyramid in RGBA16F, 0 = RGBA8
    uint32_t vao_ = 0, vbo_ = 0;
    EGLSurface out_ = EGL_NO_SURFACE;
    bool outIsWindow_ = false;
    bool outIsPbuffer_ = false;
    int w_ = 1, h_ = 1;
    bool ok_ = false;
};

}  // namespace am
