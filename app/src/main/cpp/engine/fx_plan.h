// Effect list of a layer (Gaussian blur, glow, RGB split, film grain, vignette, chroma key + the v140 set: directional /
// radial blur, sharpen, colour grading, distortion, stylize) and the pure maths behind it: parameter -> pixels, Gaussian kernels for the GPU (linear-sampling trick), pyramid sizes.
// No OpenGL in here, so it is covered by a host test (tests/fx_plan_selftest.cpp).
#pragma once
#include <cstdint>

namespace am {

enum FxType : int {
    FX_NONE = 0,
    FX_BLUR = 1,       // p0 blurriness 0..100
    FX_GLOW = 2,       // p0 threshold 0..100, p1 radius 0..100, p2 intensity 0..300 (%)
    FX_RGB_SPLIT = 3,  // p0 amount 0..100, p1 angle 0..360, p2 radial 0..100
    FX_GRAIN = 4,      // p0 amount 0..100, p1 size 0..100, p2 colour 0..100
    FX_VIGNETTE = 5,   // p0 amount 0..100, p1 size 0..100, p2 softness 0..100
    FX_CHROMA = 6,     // p0 key hue 0..360, p1 key strength 0..100, p2 similarity, p3 smoothness, p4 spill (all 0..100),
                       // p5 key brightness 0..100, p6 brightness match 0..100 (0 = brightness ignored, for green / blue screens)
    // ---- v140: these run through the four shared "shaded" programs (see fxShaderOf). The p0..p6 are the RAW slider values
    // of the Effects panel (ClipFx.kt), the shaders turn them into pixels. ----
    FX_DIR_BLUR = 7,         // p0 angle 0..360, p1 length 0..100, p2 trail 0..100 (0 = both ways, 100 = one-sided comet tail)
    FX_RADIAL_BLUR = 8,      // p0 amount 0..100, p1 spin(0)..zoom(100), p2 centre x 0..100 %, p3 centre y 0..100 %
    FX_SHARPEN = 9,          // p0 amount 0..300 %, p1 radius 0..100, p2 threshold 0..100, p3 halo control 0..100
    FX_EXPOSURE = 10,        // p0 EV -5..5, p1 offset -100..100, p2 gamma 20..300 %, p3 highlight rolloff 0..100
    FX_BRIGHT_CONTRAST = 11, // p0 brightness -100..100, p1 contrast -100..100
    FX_HUE_SAT = 12,         // p0 hue -180..180 deg, p1 saturation -100..100, p2 lightness -100..100, p3 vibrance -100..100
    FX_LEVELS = 13,          // p0 input black 0..255, p1 input white 0..255, p2 gamma 10..500 %, p3 output black, p4 output white (0..255)
    FX_COLOR_BALANCE = 14,   // p0/p1 shadows hue 0..360 / amount 0..100, p2/p3 midtones, p4/p5 highlights, p6 preserve luminosity 0..100
    FX_TINT = 15,            // p0 shadow hue, p1 shadow strength, p2 highlight hue, p3 highlight strength, p4 amount (0..100)
    FX_POSTERIZE = 16,       // p0 levels 2..32, p1 mix 0..100
    FX_WAVE = 17,            // p0 height 0..100, p1 wavelength 0..100, p2 direction 0..360, p3 phase 0..360, p4 speed -100..100
    FX_TWIRL = 18,           // p0 angle -720..720, p1 radius 0..100, p2 centre x, p3 centre y (0..100 %)
    FX_BULGE = 19,           // p0 amount -100..100, p1 radius 0..100, p2 centre x, p3 centre y
    FX_MIRROR = 20,          // p0 centre x, p1 centre y (0..100 %), p2 angle 0..360
    FX_KALEIDO = 21,         // p0 segments 2..24, p1 rotation -180..180, p2 centre x, p3 centre y, p4 zoom 20..300 %, p5 source angle 0..360
    FX_TURBULENT = 22,       // p0 amount 0..100, p1 size 1..100, p2 complexity 1..6, p3 evolution 0..360, p4 speed -100..100
    FX_MOSAIC = 23,          // p0 block size 1..100, p1 gap 0..40 %
    FX_HALFTONE = 24,        // p0 dot size 2..60, p1 angle 0..360, p2 softness 0..100, p3 colour 0..100, p4 mix 0..100
    FX_SCANLINES = 25,       // p0 intensity 0..100, p1 line size 1..20, p2 RGB mask 0..100, p3 flicker 0..100, p4 roll speed -100..100
    FX_GLITCH = 26,          // p0 amount 0..100, p1 slice size 1..100, p2 RGB split 0..100, p3 rate 1..24 / s, p4 jitter 0..100
    // ---- v149: 27 more effects, same four shared programs (new modes) ----
    FX_INVERT = 27,          // p0 amount 0..100, p1 luminance only 0..100 (0 = invert every channel, 100 = flip brightness, keep the colour)
    FX_BW = 28,              // p0 red / p1 green / p2 blue weight -50..200 %, p3 amount 0..100, p4 tone hue 0..360, p5 tone amount 0..100
    FX_THRESHOLD = 29,       // p0 level 0..255, p1 softness 0..100, p2 mix 0..100
    FX_TEMPERATURE = 30,     // p0 temperature -100..100 (cool..warm), p1 tint -100..100 (green..magenta)
    FX_SHADOW_HIGHLIGHT = 31,// p0 shadows 0..100, p1 highlights 0..100, p2 tonal width 0..100, p3 midtone contrast -100..100
    FX_LEAVE_COLOR = 32,     // p0 hue 0..360, p1 range 0..100, p2 softness 0..100, p3 fade the others 0..100
    FX_CINEMATIC = 33,       // p0 amount 0..100, p1 contrast 0..100, p2 fade 0..100, p3 saturation -100..100
    FX_SOLARIZE = 34,        // p0 threshold 0..100, p1 amount 0..100
    FX_LENS_BLUR = 35,       // p0 radius 0..100, p1 highlight boost 0..100, p2 threshold 0..100
    FX_TILT_SHIFT = 36,      // p0 blur 0..100, p1 focus y 0..100 %, p2 focus width 0..100, p3 angle 0..180, p4 transition 0..100
    FX_SURFACE_BLUR = 37,    // p0 radius 0..100, p1 threshold 0..100 (bigger = smooths more, only edges stay)
    FX_LIGHT_RAYS = 38,      // p0 threshold 0..100, p1 length 0..100, p2 intensity 0..300 %, p3 centre x, p4 centre y (%), p5 colour hue 0..360, p6 colour amount 0..100
    FX_RIPPLE = 39,          // p0 amplitude 0..100, p1 wavelength 0..100, p2 centre x, p3 centre y (%), p4 radius 0..100, p5 speed -100..100, p6 phase 0..360
    FX_LENS_DISTORT = 40,    // p0 curvature -100..100, p1 zoom 50..200 %, p2 chromatic aberration 0..100, p3 centre x, p4 centre y (%)
    FX_POLAR = 41,           // p0 interpolation 0..100, p1 direction 0 (rect -> polar) / 1 (polar -> rect)
    FX_TILE = 42,            // p0 tiles x 1..10, p1 tiles y 1..10, p2 mirror edges 0/1, p3 / p4 phase x / y -100..100 %, p5 / p6 scroll speed x / y -100..100
    FX_SHAKE = 43,           // p0 amount 0..100, p1 rate 1..24 /s, p2 rotation 0..100, p3 scale fill 0..100, p4 smoothness 0..100, p5 seed 0..100
    FX_EMBOSS = 44,          // p0 amount 0..300 %, p1 direction 0..360, p2 relief 1..20, p3 keep colour 0..100
    FX_FIND_EDGES = 45,      // p0 strength 0..300 %, p1 thickness 1..10, p2 glow style 0..100 (0 = dark lines on white), p3 colour 0..100, p4 mix 0..100
    FX_CARTOON = 46,         // p0 colour levels 2..16, p1 edge strength 0..100, p2 edge thickness 1..6, p3 smoothing 0..100, p4 mix 0..100
    FX_SKETCH = 47,          // p0 line density 1..100, p1 darkness 0..100, p2 angle 0..360, p3 edge strength 0..100, p4 colour 0..100, p5 mix 0..100
    FX_OIL = 48,             // p0 brush size 0..100, p1 colour boost 0..100
    FX_STROBE = 49,          // p0 rate 1..24 /s, p1 strength 0..100, p2 flash length 1..100 %, p3 invert 0..100 (0 = white flash)
    FX_VHS = 50,             // p0 amount 0..100, p1 wobble, p2 colour bleed, p3 tracking bar, p4 tape noise, p5 colour fade (all 0..100)
    FX_OLD_FILM = 51,        // p0 scratches, p1 dust, p2 flicker, p3 gate weave, p4 sepia, p5 fade (all 0..100)
    FX_LIGHT_LEAK = 52,      // p0 intensity 0..100, p1 hue 0..360, p2 size 0..100, p3 speed -100..100, p4 position 0..100
    FX_LENS_FLARE = 53,      // p0 centre x, p1 centre y (%), p2 brightness 0..200 %, p3 size 0..100, p4 ghosts 0..100, p5 streak 0..100, p6 hue 0..360
    FX_LAST = FX_LENS_FLARE,  // last of the v140 / v149 shaded effects (v160 adds 59 / 60 above; fxShaderOf is the map)
    // v157: Copy Background. Not a shaded effect: it needs the frame drawn so far (see Renderer::fxCopyBg).
    FX_COPY_BG = 54,         // p0 amount 0..100 % (how much of the picture behind the layer replaces the layer, cut to the layer's shape)
    // v158: Motion Blur. Not a shaded effect either: Kotlin resolves it per frame to FX_DIR_BLUR (see MotionBlurEval),
    // so it must never reach the shaders. It sits above FX_MAX on purpose: fxParseOps skips it, runEffects no-ops it.
    FX_MOTION_BLUR = 55,    // p0 strength 0..100 % (the tune); the angle / length are computed per frame, never stored
    // v159: Repeat. Not a shaded effect either: Kotlin draws the layer once per copy in the overlay loops
    // (see RepeatEval), so it must never reach the shaders. Same rule as Motion Blur above.
    FX_REPEAT = 56,         // p0 count, p1/p2 offset x/y (preview px), p3 angle deg, p4 scale %, p5 alpha % — per copy
    // v160: two more shaded effects, ABOVE the Kotlin-resolved block on purpose but still parsed: they run
    // through the shared shaded programs like 7..53 (fxShaderOf maps them), so fxParseOps accepts them
    // explicitly and runEffects dispatches on fxShaderOf, not on a numeric range. 57 / 58 stay skipped.
    FX_LONG_SHADOW = 59,    // p0 length % of picture height, p1 angle 0..360, p2 opacity 0..100, p3 solidity 0..100,
                            // p4 fade 0..100, p5 shadow colour packed 0xRRGGBB (importer only; 0 = black)
    FX_COLOR_TUNE = 60,     // p0 lift hue 0..360, p1 lift strength 0..100, p2 lift luminance -100..100,
                            // p3 gain hue 0..360, p4 gain strength 0..100, p5 gain luminance -100..100
    // v161: Solid Color (Alight Motion's `solidcolor`): the picture's colours are replaced by one
    // colour, its alpha silhouette kept — kFxColor mode 16. Accepted explicitly like 59 / 60.
    FX_SOLID_COLOR = 61,    // p0/p1/p2 the colour 0..255, p3 strength 0..100 (100 = fully flat)
    // v162: Alight Motion's own Mirror and Glow Scan (AM's effect definitions were recovered).
    // Mirror is shaded (kFxWarp mode 11): the WHOLE layer's sampling flipped — it is NOT
    // FX_MIRROR above (a centre/angle reflect-half). Accepted explicitly like 59..61.
    FX_AM_MIRROR = 62,      // p0 type (0 = horizontal flip, 1 = vertical), p1 blend mode (0 Normal, 1 Multiply,
                            // 2 Screen, 3 Over, 4 Under), p2 alpha 0..100, p3 offset -200..200 (% of the layer)
    // Glow Scan is a dedicated two-texture pass like FX_GLOW (the shader needs the layer AND its
    // blurred copy), so it is not in fxShaderOf: runEffects dispatches it to Renderer::fxGlowScan.
    FX_GLOW_SCAN = 63,      // p0 threshold 0..100, p1 radius 0..100, p2 power 0..4, p3 smoothing 0..200 (%:
                            // AM's strength, the pre-blur), p4 fill 0..100, p5 alpha 0..100,
                            // p6 fill colour packed 0xRRGGBB
    FX_MAX = FX_COPY_BG
};

constexpr int kFxMaxOps = 12;
constexpr int kFxStride = 8;  // floats per effect in the JNI array: type, p0..p6

struct FxOp {
    int type = FX_NONE;
    float p[7] = {0, 0, 0, 0, 0, 0, 0};
};

// Reads `n` floats (groups of kFxStride) into `out` (room for kFxMaxOps). Unknown types are skipped.
int fxParseOps(const float* flat, int n, FxOp* out);

// ---- Gaussian blur ----
// Blurriness 0..100 -> sigma in picture pixels. `unit` = picture height / 1080, so the look does not depend
// on the resolution of the source (preview = export).
float fxBlurSigma(float blurriness, float unit);

struct BlurPlan {
    int shift = 0;       // the blur runs on a picture 2^shift times smaller (0 = full size)
    float sigmaLow = 0;  // sigma in pixels of that smaller picture
};
BlurPlan fxPlanBlur(float sigmaPx);

// One-sided kernel for the "linear sampling" Gaussian: a centre tap plus pairs of neighbouring texels
// fetched with ONE bilinear lookup each. offsets are in texels (> 0), the kernel is mirrored.
constexpr int kFxMaxTaps = 20;
struct GaussKernel {
    int n = 0;  // pairs
    float centre = 1.f;
    float offset[kFxMaxTaps] = {0};
    float weight[kFxMaxTaps] = {0};
};
GaussKernel fxGaussKernel(float sigma);

// ---- Glow ----
constexpr int kFxGlowLevels = 6;
struct GlowPlan {
    float threshold;  // 0..1 of the brightest channel
    float knee;       // soft threshold width
    float spread;     // how much each coarser level adds (0..1): bigger = wider glow
    float intensity;  // multiplier on the glow
};
GlowPlan fxPlanGlow(const FxOp& op);

// ---- Chroma key ----
// The key colour is a point in the CbCr plane (BT.601 full range, both axes about -0.5..0.5). A pixel is keyed out by its
// distance to that point there, so the brightness of the screen (shadows, folds, hot spots) does not matter.
struct ChromaPlan {
    float keyCb, keyCr;  // key colour in the CbCr plane
    float dirCb, dirCr;  // unit vector from the grey axis to the key colour (for the spill removal)
    float tol;           // distance below which a pixel is fully transparent
    float soft;          // width of the soft edge above `tol`
    float spill;         // 0..1 how much of the key colour is taken out of the kept pixels (0 for a grey key: no direction)
    float keyV;          // brightness (brightest channel, 0..1) of the key colour
    float cs;            // factor on the chroma distance: 3 for a grey key (colours must count), 1 for a coloured key
    float vw;            // 0..1 how much the brightness difference counts in the distance (grey / white / black keys)
};
// Chroma (Cb, Cr) of an RGB colour (each 0..1).
void fxChromaOf(float r, float g, float b, float* cb, float* cr);
// Chroma used for the key distance: the chroma divided by the brightest channel (floored at 0.2 so that noise in the
// blacks is not blown up). A screen in shadow then has the same key chroma as a lit one.
void fxChromaKeyed(float r, float g, float b, float* cb, float* cr);
// Colour wheel -> RGB at full value (hue 0..360, saturation 0..1).
void fxHsvToRgb(float hue, float sat, float* r, float* g, float* b);
ChromaPlan fxPlanChroma(const FxOp& op);
// Eyedropper (ANY colour, also grey / white / black / skin): the key hue (0..360), key strength (0..100), key brightness
// and brightness match (0..100; a dull colour is told apart from others by its brightness) that make the plan's key colour equal the chroma of a
// picked colour (each 0..1). The Kotlin copy is ClipFxLibrary.keyFromRgb.
void fxKeyFromRgb(float r, float g, float b, float* hue, float* strength, float* keyV, float* match);
// The same maths as the shader (CPU reference, used by the host test): coverage 0..1 of a pixel (1 = keep).
float fxChromaKeep(const ChromaPlan& pl, float cb, float cr, float v = 0.f);

// ---- v140: the shared "shaded" effects ----
// Four fragment programs run every effect from FX_DIR_BLUR on; `mode` is the branch inside the program (uMode in GLSL).
enum FxGroup : int { FXG_NONE = -1, FXG_BLUR2 = 0, FXG_COLOR = 1, FXG_WARP = 2, FXG_STYLE = 3 };
struct FxShaderRef {
    int group = FXG_NONE;
    int mode = 0;
};
FxShaderRef fxShaderOf(int type);
// true when the effect would not change the picture at all (default / neutral values): the pass is skipped.
bool fxIsIdentity(const FxOp& op);
// CPU reference of the Levels effect (one channel, 0..1, sRGB values): input black / white (0..255), gamma (%), output black / white (0..255).
float fxLevelsMap(float v, float inBlack, float inWhite, float gammaPct, float outBlack, float outWhite);
// CPU reference of the Brightness & Contrast curve (one channel, 0..1): brightness / contrast -100..100.
float fxBrightContrast(float v, float brightness, float contrast);
// CPU reference of the Color Tune effect (the same maths as kFxColor mode 15): Lift / Gain colour-disc
// pushes (hue 0..360, strength 0..100) and luminance shifts (-100..100), weighted by the pixel's own
// luminance with Alight's published curves (Lift (1-y)^2, Gain y^2). r / g / b are 0..1, in and out.
void fxColorTuneMap(float r, float g, float b, float liftHue, float liftStrength, float liftLum,
                    float gainHue, float gainStrength, float gainLum, float* outR, float* outG, float* outB);
// CPU reference of the Solid Color effect (the same maths as kFxColor mode 16): the pixel's straight
// r / g / b (0..1) mixed toward the solid colour (0..255) by the strength (0..100). The pixel's alpha
// is not touched by the effect, so it is not a parameter here.
void fxSolidColorMap(float r, float g, float b, float cr, float cg, float cb, float strength,
                     float* outR, float* outG, float* outB);
// CPU reference of Alight's Mirror blend (the same maths as kFxWarp mode 11): S is the pixel and F the
// flipped pixel, both PREMULTIPLIED (r / g / b / a, 0..1); the blend mode is AM's (0 Normal, 1 Multiply,
// 2 Screen, 3 Over, 4 Under) and the result is mixed over S by alpha (0..1). AM unpremultiplies S for
// Multiply / Screen (texColor.rgb / texColor.a); the division is floored at 1e-5 so a fully transparent
// pixel stays 0 instead of NaN — invisible, since the result is scaled by S.a there anyway.
void fxMirrorMap(float sr, float sg, float sb, float sa, float fr, float fg, float fb, float fa,
                 int blendMode, float alpha, float* outR, float* outG, float* outB, float* outA);
// CPU reference of Alight's Glow Scan (the same maths as Renderer::fxGlowScan's shader): `in` is the
// layer pixel and `base` its blurred copy, both PREMULTIPLIED (0..1). threshold / radius / fill / alpha
// are 0..1, power 0..4, the fill colour is straight r / g / b (0..1), blend 0..1 (AM multiplies it by
// 0.99 in the glow mix). Outside the band the output is the input scaled by fill; inside, it mixes
// toward base + glow by alpha. The rgb is clamped to the alpha at the end (the engine's premultiplied
// invariant, the kGlowComp precedent) — a no-op on opaque layers.
void fxGlowScanMap(float inR, float inG, float inB, float inA, float baseR, float baseG, float baseB, float baseA,
                   float threshold, float radius, float power, float fillR, float fillG, float fillB,
                   float blend, float fill, float alpha, float* outR, float* outG, float* outB, float* outA);
// The FX_BLUR blurriness (p0) a Glow Scan smoothing value (%, AM strength x 100) produces: the
// importer's applyGaussianBlur calibration (AM strength x 50), so the scan's pre-blur matches the
// blur the same strength gives as a standalone effect.
float fxGlowScanBlurriness(float smoothingPct);
// Posterize: one channel 0..1 snapped to `levels` steps.
float fxPosterize(float v, float levels);
// Units of the animated effects: phase of the Wave Warp / Turbulent Displace at a pattern frame (24 / s), in turns.
float fxAutoPhase(int frame, float speed);

// ---- other maths ----
float fxSplitPixels(float amount, float unit);  // RGB split: 100 -> 2.5 % of the height
float fxGrainCell(float size, float unit);      // grain size in picture pixels (1..4 * unit)
int fxGrainFrame(int64_t timeMs);               // grain pattern changes 24 times a second
}  // namespace am
