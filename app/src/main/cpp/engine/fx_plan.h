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
    // v163: the next tier of Alight's own effects, from the same recovered definitions (hunt-staging/
    // defs-batch2). Four of them need more than the 7 native slots, so they own a second group (an
    // EXT type) that ClipFxLibrary.toNative emits right after the main group; fxParseOps folds it
    // into the owner's q[7] and never surfaces it as an op of its own.
    FX_LUMA_KEY = 64,       // p0 low threshold 0..100 (KEYFRAMED in all 19 corpus instances: rides the
                            // AmFxEval tail, slot 0), p1 high threshold 0..100, p2 feather 0..100,
                            // p3 invert 0/1, p4 weighted luma 0/1. Dedicated pass (it scales alpha).
    FX_FOUR_COLOR_GRADIENT = 65,  // p0..p3 colours 1..4 packed 0xRRGGBB, p4 blend 25..200 (%),
                                  // p5 alpha 0..100, p6 blend mode (0 Normal, 1 Multiply, 2 Screen);
                                  // q0..q3 offsets 1..4, each an AM point packed into one float
                                  // (12-bit signed fields, see fxPackPoint in ClipFx.kt). Dedicated pass.
    FX_FOUR_COLOR_GRADIENT_EXT = 66,
    FX_TURBULENT_DISPLACE_AM = 67,  // p0 intensity 0..250 (%), p1 scale 1..5000 (%), p2 direction
                                    // (0 X, 1 Y, 2 XY, 3 R), p3 evolution -5000..5000 (%), p4 octaves 1..9,
                                    // p5 screen space 0/1, p6 offs -100..100 (%); q0 stretch (%),
                                    // q1 seed (%), q2/q3 position (AM units), q4/q5 parallax (AM units).
                                    // Intensity AND scale are keyframed in all 39 corpus instances:
                                    // two AmFxEval tails. Dedicated pass (AM's simplex noise).
    FX_TURBULENT_DISPLACE_AM_EXT = 68,
    FX_SWIRL_AM = 69,       // p0 strength -50..50 (%) (keyframed instances ride the tail, slot 0),
                            // p1 radius 0..80 (%), p2/p3 centre point (AM units), p4 version (4 or 3:
                            // swirl4 / swirl3 differ in centre units + aspect rule). kFxWarp mode 13.
    FX_STRETCH_SEGMENT = 70,  // p0 angle (deg), p1 stretch 0..900 (AM units), p2 offset (AM units),
                              // p3 smooth 0..100 (%). kFxWarp mode 12.
    FX_HALFTONE_DOTS = 71,  // p0 amount 0..500 (AM units), p1 strength 0..100 (%), p2 angle (deg),
                            // p3 phase -100..100 (%), p4/p5 colours 1/2 packed 0xRRGGBB. kFxWarp mode 14.
    FX_PIXELATE_AM = 72,    // p0 size 1..500 (KEYFRAMED in 1 of 4 corpus instances: rides the tail at
                            // scale 1, slot 0), p1/p2 stretch x/y (%), p3 angle (deg), p4 vignette (%),
                            // p5 screen space 0/1, p6 threshold (%); q0 saturation (%), q1/q2 offset
                            // (AM units). Dedicated pass (the block-centre sampling + cubic vignette).
    FX_PIXELATE_AM_EXT = 73,
    FX_REPLACE_COLOR = 74,  // p0 old colour packed 0xRRGGBB, p1 new colour packed, p2 threshold
                            // 0..400 (%), p3 feather 0..200 (%), p4 alpha 0..100, p5 lock luminance 0/1.
                            // kFxColor mode 17 (the program's straight-space maths is AM's own).
    // v164: the parked tier of Alight's own effects, hunt round 2 — every definition recovered
    // from the same public APK asset dump (hunt-staging/defs-batch3, see its SOURCES.md). Two of
    // the tier stayed parked at v164 and shipped at v165 (defs-batch4): lightglow (re-reading
    // the def showed all four passes share ONE shader branched on acPass — no host-named
    // downsample shader exists in it) and the displacemap family (the defs' srcType evidence —
    // inputImg is "content", comp is "comp" — pins comp as the composite BELOW the layer,
    // which is the renderer's existing backdrop capture).
    FX_CHROMA_KEY_AM = 75,  // p0 key colour packed 0xRRGGBB, p1 threshold 0..100 (% of AM's raw),
                            // p2 feather 0..100 (%), p3 defringe 0/1, p4 invert 0/1. Dedicated
                            // pass (it scales alpha and defringes in premultiplied space).
    FX_COLOR_BALANCE_AM = 76,  // p0..p6 crLow, mgLow, ybLow, crMid, mgMid, ybMid, crHigh (AM raw
                               // -100..100); q0 mgHigh, q1 ybHigh, q2 fix luminance 0/1.
                               // kFxColor mode 18.
    FX_COLOR_BALANCE_AM_EXT = 77,
    FX_COLOR_TEMP_AM = 78,  // p0 temperature (AM units: Kelvin / 1000, 1..40), p1 strength 0..100
                            // (%). kFxColor mode 19.
    FX_COLOR_TINT_AM = 79,  // p0 tint hue (degrees, AM raw), p1 strength 0..100 (%).
                            // kFxColor mode 20.
    FX_SPIN_BLUR_AM = 80,   // p0 angle (degrees, AM raw; KEYFRAMED instances ride the tail,
                            // slot 0), p1/p2 centre point (AM units, /500 in the shader).
                            // kFxBlur2 mode 8.
    FX_OFFSET_AM = 81,      // p0/p1 offset x/y (AM units, /1000 of the layer; keyframed instances
                            // ride two tails, slots 0 and 1), p2 feather 0..100 (%), p3 mask 0/1.
                            // kFxWarp mode 15.
    FX_RADIAL_WIPE_AM = 82,  // p0 progress (degrees 0..360; keyframed instances ride the tail,
                             // slot 0), p1 feather (degrees), p2 direction (0 CW, 1 CCW, 2 both),
                             // p3 repeat count, p4 centre point packed (AM units, /1200),
                             // p5 offset (degrees, the start angle). kFxWarp mode 16.
    FX_FIND_EDGES_AM = 83,  // p0 smoothing (AM raw), p1 threshold 0..100 (%), p2 invert 0/1.
                            // kFxStyle mode 14. NOT FX_FIND_EDGES (the app's own edge look).
    FX_HEX_SHIFT_AM = 84,   // p0 strength (AM raw; keyframed instances ride the tail, slot 0),
                            // p1 size (AM units), p2/p3 stretch x/y (%), p4 angle (degrees, AM
                            // raw; keyframed instances ride the tail, slot 4), p5 seed,
                            // p6 screen space 0/1; q0 mask to layer 0/1, q1 position point
                            // packed (AM units). kFxWarp mode 17.
    FX_HEX_SHIFT_AM_EXT = 85,
    FX_SOFT_GLOW_AM = 86,   // p0 strength 0..100 (%: the pre-blur, AM raw x100), p1 brightness
                            // (%), p2 contrast (%), p3 highlights (%), p4 colour packed
                            // 0xRRGGBB, p5 blend (%), p6 alpha (%); q0 outside alpha (%) — the
                            // backdrop branch is NOT reproduced (the importer warns when > 0).
                            // Dedicated pass (it needs the layer AND its blurred copy).
    FX_SOFT_GLOW_AM_EXT = 87,
    FX_FILL_BEHIND_AM = 88,  // p0 strength (%), p1 feather (%), p2 gamma (x100), p3 tint strength
                             // (%), p4 tint colour packed 0xRRGGBB, p5 edge strength (%),
                             // p6 scan lines (%); q0 scan line count (AM raw), q1 background
                             // scale (%), q2 edge colour packed 0xRRGGBB, q3/q4 background
                             // offset x/y (AM units; keyframed instances ride two tails at
                             // values 10/11), q5 alpha (%), q6 saturation (%). Dedicated pass
                             // (blurred copy + composite). AM's brightness / fgAlpha / fgScale /
                             // fgOffset have no slot: the importer warns when one is not at its
                             // default (all are, in the corpus instance).
    FX_FILL_BEHIND_AM_EXT = 89,
    // v165: Alight's effects tier 3, hunt round 3 (hunt-staging/defs-batch4, see its
    // SOURCES.md). Every value is stored in AM's own units unless noted; keyframed primaries
    // ride AmFxEval tails like the earlier tiers. Only extrude stays parked (its vertex
    // shader needs a host-supplied orient matrix from AM's 3D system — see ROADMAP).
    FX_WAVE_WARP_AM = 90,   // p0 phase (turns), p1 direction a1d (deg), p2 spacing m1 (AM raw),
                            // p3 magnitude m2 (AM raw), p4 warp angle a2d (deg), p5 damping
                            // -1..1, p6 damping spacing -1..1; q0 damping origin 0..1,
                            // q1 screen space 0/1 (the pattern is layer-anchored here, like
                            // Hex Shift's). kFxWarp mode 18.
    FX_WAVE_WARP_AM_EXT = 91,
    FX_CIRCULAR_RIPPLE_AM = 92,  // p0 strength (AM raw -1..1), p1 radius 0..0.8, p2 frequency
                                 // (AM raw), p3 feather 0..1, p4 phase (turns), p5/p6 centre
                                 // point (AM units, /1000 in the shader). circularripple's
                                 // shader is byte-identical to circularripple2's: one mapping.
                                 // kFxWarp mode 19.
    FX_STRETCH_AM = 93,     // p0 scale (AM raw 0.01..50), p1 angle (deg), p2 content only 0/1,
                            // p3 version (1 = stretch, 2 = stretch2: their output rules differ
                            // under contentOnly). kFxWarp mode 20.
    FX_SQUEEZE_AM = 94,     // p0 strength (AM raw -2..2; the def pins the axis rule build 101
                            // refused to guess). kFxWarp mode 21.
    FX_FRACTAL_WARP_AM = 95,  // p0 magnitude (AM raw), p1 scale, p2 intensity (lacunarity),
                              // p3 octaves 1..9, p4/p5 the offs point components (AM units,
                              // separate so keyframed points animate), p6 parallax x;
                              // q0 parallax y. kFxWarp mode 22.
    FX_FRACTAL_WARP_AM_EXT = 96,
    FX_STRIPES_AM = 97,     // p0 amount (AM raw), p1 width 0..1, p2 angle (deg), p3 phase
                            // (AM raw), p4 colour packed 0xRRGGBB (its alpha is always 1:
                            // AM declares the colour alpha="false"), p5 smoothing 0..0.5,
                            // p6 punch out 0/1. kFxWarp mode 23.
    FX_TURBULENT_DISPLACE3_AM = 98,  // p0 intensity (AM raw), p1 scale (AM raw), p2 evolution,
                                     // p3 seed, p4 direction (0 X, 1 Y, 2 XY, 3 R, 4 phi-r,
                                     // 5 phi), p5 octaves 1..9, p6 offs (bias) -1..1;
                                     // q0/q1 parallax point components, q2/q3 position point
                                     // components, q4/q5 stretch x/y, q6 flags (screen
                                     // space + 2 * single axis; screen space stays
                                     // layer-anchored here). kFxWarp mode 24.
    FX_TURBULENT_DISPLACE3_AM_EXT = 99,
    FX_ROUGHEN_EDGES_AM = 100,  // p0 strength (AM raw), p1 detail, p2 evolution, p3 seed,
                                // p4 octaves 1..9. turbulentdisplace3's shader with direction
                                // R hardcoded; the output is the layer times the displaced
                                // sample's alpha (edge erosion). kFxWarp mode 25.
    FX_STREAKS_AM = 101,    // p0 strength (AM raw 0..1), p1 angle (deg), p2 bias -1..1,
                            // p3 alpha 0..1, p4/p5/p6 the R/G/B combine modes; q0 the A
                            // combine mode (modes: 0 min, 1 max, 2 off, 3 average).
                            // kFxBlur2 mode 9.
    FX_STREAKS_AM_EXT = 102,
    FX_COLOR_HOT_AM = 103,  // p0..p2 the colour hue-disc (hue deg, saturation 0..1, bias),
                            // p3..p5 the tint hue-disc. kFxColor mode 21.
    FX_COLORIZE_AM = 104,   // p0 tint hue (deg), p1 tint amount 0..1 (the hue-disc's y).
                            // kFxColor mode 22.
    FX_HIGHLIGHT_SHADOW_AM = 105,  // p0 shadows 0..2, p1 highlights 0..2 (GPUImage's formula,
                                   // verbatim in AM's def). kFxColor mode 23.
    FX_SPOT_COLOR_AM = 106,  // p0 colour packed 0xRRGGBB, p1 threshold (AM raw), p2 feather
                             // (AM raw), p3 saturation (AM raw), p4 choke. kFxColor mode 24.
    FX_DISSOLVE_AM = 107,   // p0 amount 0..1, p1 seed. kFxStyle mode 15.
    FX_SIMPLE_STARS_AM = 108,  // p0 scale, p1 star size, p2 star feather, p3 colour packed
                               // 0xRRGGBB, p4 star count 1..50, p5 brightness, p6 blend mode
                               // (0 normal, 1 multiply, 2 screen, 3 punchout); q0/q1/q2 the
                               // star parallax x/y/z components (AM units), q3 alpha 0..1.
                               // kFxStyle mode 16.
    FX_SIMPLE_STARS_AM_EXT = 109,
    FX_DOTS_AM = 110,       // p0 scale, p1 phase (turns), p2 radius, p3 feather, p4/p5 the two
                            // colours packed 0xRRGGBB, p6 density 1..13; q0/q1 the position
                            // point components (AM units), q2 fill 0..1, q3 alpha 0..1,
                            // q4 blend mode (0 normal, 1 multiply, 2 screen), q5 seed,
                            // q6 slack 0..1. kFxStyle mode 17.
    FX_DOTS_AM_EXT = 111,
    FX_RIDGES_AM = 112,     // p0 intensity, p1 scale, p2 evolution, p3 seed, p4 octaves 1..9,
                            // p5/p6 colours 1/2 packed 0xRRGGBB; q0/q1 the offs point
                            // components, q2/q3 the parallax point components, q4/q5
                            // stretch x/y, q6 version (1 = ridges: scalar stretch,
                            // (st-.5)*scaleVec; 2 = ridges2: point stretch, (st-.5)/scaleVec).
                            // kFxStyle mode 18.
    FX_RIDGES_AM_EXT = 113,
    FX_BEND_WARP_AM = 114,  // p0 amount (deg), p1 offset 0..1, p2 origin type (0 left,
                            // 1 right, 2 top, 3 bottom), p3 opposite type (0 freeze, 1 bend
                            // same, 2 bend opposite), p4 high limit 0..1, p5 low limit 0..1.
                            // kFxWarp mode 26.
    FX_DISPLACE_MAP_AM = 115,  // p0/p1 the offs point components (AM units, /1000 in the
                               // shader), p2 X source (0 luma, 1 R, 2 G, 3 B), p3 Y source,
                               // p4 edges (0 repeat, 1 mirror, 2 transparent, 3 tile),
                               // p5 from centre 0/1, p6 invert 0/1. displacemap3's shader is
                               // byte-identical to displacemap2's. Dedicated backdrop pass
                               // (samples the composite below the layer, like Copy
                               // Background's source).
    FX_DISPLACE_MAP_RAD_AM = 116,  // p0 offs angle (deg), p1 offs radius (AM raw), p2/p3 the
                                   // phi/rho sources, p4 edges, p5 from centre 0/1,
                                   // p6 invert 0/1; q0/q1 the centre point components
                                   // (AM units). Dedicated pass.
    FX_DISPLACE_MAP_RAD_AM_EXT = 117,
    FX_SMOOTH_BEVEL_AM = 118,  // p0 radius (AM raw), p1 diffuse angle (deg), p2 specular
                               // angle (deg), p3 diffuse intensity 0..1, p4 specular
                               // intensity 0..1, p5 diffuse elevation, p6 specular elevation;
                               // q0 spec coefficient, q1 diffuse colour packed 0xRRGGBB,
                               // q2 specular colour packed. Dedicated two-pass (X distance
                               // scan into a packed buffer, then the Y scan + shading).
    FX_SMOOTH_BEVEL_AM_EXT = 119,
    FX_LIGHT_GLOW_AM = 120,  // p0 strength (AM raw), p1 threshold 0..1, p2 intensity,
                             // p3 colour packed 0xRRGGBB, p4 blend 0..1, p5 halo, p6 alpha 0..1.
                             // Dedicated four-pass (bright-pick + incremental Gaussian at
                             // half res, composite, copy up).
    FX_INNER_GLOW_AM = 121,  // p0 radius (AM raw 0..400), p1 alpha 0..1, p2 hardness 0..1,
                             // p3 colour packed 0xRRGGBB (the def's colour alpha is never
                             // read by its shader), p4 blend mode (0 normal, 1 multiply,
                             // 2 screen). Dedicated two-pass distance scan, like Smooth
                             // Bevel's buffer (X scan, then Y scan + shade): the glow
                             // colour blends over the layer by alpha * p, p the reversed
                             // smoothstep of the distance to the nearest transparent pixel.
    FX_DARK_GLOW_AM = 122,   // p0 strength % (AM raw x100), p1 threshold %, p2 colour packed
                             // 0xRRGGBB, p3 blend %, p4 alpha %. Dedicated pass chain
                             // (Renderer::fxDarkGlowAm): extract the dark parts, blur them,
                             // multiply back over the layer (AM's darkglow def, build 120).
    // v173: Alight's s3d-extrude and its def-less sibling extrudeflat — dedicated raymarch
    // passes (Renderer::fxExtrudeAm). Extrude's def (recovered from two independent dumps,
    // byte-identical shaders) renders the layer's silhouette as a 3D box: a gaussianblur
    // buffer pass feeds a sphere-tracing march of up to MAX_ITER steps. Slot layout
    // (AM units throughout):
    //   p: [0] depth, [1] scale, [2] widthRatio, [3] heightRatio, [4] interiorBlend,
    //      [5] fadeBlend, [6] shadingType (0 flat, 1 point light, 2 directional,
    //      3 depth map, 4 normal map, 5 normal & depth)
    //   q: [0..2] rotate xyz (degrees), [3..5] position xyz, [6] interiorColor packed
    //   r: [0] fadeColor packed, [1] lightColor packed, [2] lightDir point packed
    //      (ClipFx packPoint), [3] lightIntensity + strength packed:
    //      round(intensity*100) + round(strength*100)*256 (both 0.01-granularity
    //      scalars; strength also feeds the blur buffer pass and the march step size),
    //      [4] shine + ambient packed: round(shine*100)*128 + round(ambient*100),
    //      [5] spec, [6] lightDepth + MAX_ITER packed: round((lightDepth+100)*2) +
    //      MAX_ITER*2048
    FX_EXTRUDE_AM = 123,
    FX_EXTRUDE_AM_EXT = 124,
    FX_EXTRUDE_AM_EXT2 = 125,
    // Extrudeflat shares the march with flat shading and no light model:
    //   p: [0] depth, [1] scale, [2] stretch x, [3] stretch y, [4] interiorBlend,
    //      [5] fadeBlend, [6] inton (0/1 — its def, recovered in hunt round 3
    //      (defs-batch8), gates the interior / fade colour mixes on it: with inton
    //      off the extruded sides keep the surface colour; pinned in build 136.
    //      All 16 corpus instances carry true with interiorBlend 1)
    //   q: like extrude's. r: [0] fadeColor packed, [1] MAX_ITER, [2..6] unused.
    // In both, keyframed rotate / position components ride AmFxEval tails from index 21.
    FX_EXTRUDE_FLAT_AM = 126,
    FX_EXTRUDE_FLAT_AM_EXT = 127,
    FX_EXTRUDE_FLAT_AM_EXT2 = 128,
    // v174: Alight's edgeglow — a dedicated three-pass chain (Renderer::fxEdgeGlowAm),
    // ported from its def (two independent dumps carry byte-identical params, pass
    // chain and shaders): pass 1 is the def's findedges-edgeglow (a Scharr Sobel on
    // the premultiplied luma, taps at +/- smoothing texels, g = clamp(|grad| -
    // threshold)) into a buffer, pass 2 is the host gaussianblur of that buffer,
    // pass 3 the def's composite. The wiring follows AM's host convention (a pass
    // reads the params its own def declares): smoothing / threshold / invert feed
    // pass 1 (findedges-edgeglow declares exactly those), strength feeds the blur
    // (its def label is literally "Blur"; the gaussianblur kernel converts it).
    // Slot layout (AM units throughout):
    //   p: [0] smoothing 1..10, [1] threshold 0..1, [2] invert 0/1, [3] strength
    //      0..2, [4] spread 0..3, [5] fillColor packed 0xRRGGBB, [6] blend 0..2
    //   q: [0] gamma, [1] brightness -1..1, [2] tintColor packed 0xRRGGBB, [3] tint 0..1
    FX_EDGE_GLOW_AM = 129,
    FX_EDGE_GLOW_AM_EXT = 130,
    // v175: Alight's effects tier 4 (corpus round 2; hunt-staging/defs-batch7 — every def
    // recovered from TWO dumps, the AM APK assets and DGameXO/openmotion, shader- and
    // structure-identical between them; the AM-dump copies are followed). All values are
    // AM's own units. The shaded three ride the shared programs; the rest are dedicated
    // passes in renderer_effects.cpp (they need buffers, a backdrop, an alpha rewrite or
    // a multi-tap loop the shared programs cannot express).
    FX_THRESHOLD_AM = 131,  // p0 threshold 0..1, p1 feather 0..1, p2 invert 0/1,
                            // p3 blend mode (0 mask, 1 multiply, 2 screen). NOT
                            // FX_THRESHOLD (the app's own level cut). kFxColor mode 25.
    FX_SPECTRALMAP_AM = 132,  // p0 phase -99..99, p1 repeat 0.1..10, p2 alpha 0..1,
                              // p3 squeeze 0..1, p4 saturation -1..1, p5 reverse 0/1,
                              // p6 scale brightness 0/1; q0/q1/q2 the red / green / blue
                              // colours packed 0xRRGGBB (their alpha never reaches the
                              // maths: the def multiplies them into rgb only).
                              // kFxColor mode 26.
    FX_SPECTRALMAP_AM_EXT = 133,
    FX_CHANNELMAP_AM = 134,  // p0..p3 the R / G / B / A channel sources (0 R, 1 G, 2 B,
                             // 3 A, 4 luma, 5 zero, 6 one). Dedicated pass: it rewrites
                             // the alpha, which the colour program's epilogue cannot.
    FX_HALFTONE_LINES_AM = 135,  // p0 amount 0..500, p1 strength 0..1, p2 angle (deg),
                                 // p3 phase (AM raw, -1000..1000), p4/p5 colours 1/2
                                 // packed 0xRRGGBB. NOT the app's HALFTONE. kFxWarp
                                 // mode 27 (halftone dots' sibling: a y-only fold).
    FX_TURBULENCE2_AM = 136,  // p0 intensity, p1 scale, p2 evolution, p3 seed,
                              // p4/p5 colours 1/2 packed 0xRRGGBB, p6 blend mode
                              // (0 normal, 1 multiply, 2 screen); q0/q1 the offs point
                              // components, q2/q3 the parallax point components,
                              // q4/q5 stretch x/y, q6 bias; r0 alpha, r1 octaves.
                              // kFxStyle mode 19 (ridges' sibling: the plain turbulence
                              // sum, no ridged fold).
    FX_TURBULENCE2_AM_EXT = 137,
    FX_TURBULENCE2_AM_EXT2 = 138,
    FX_CHECKER2_AM = 139,  // p0 amount 0.5..200, p1 angle (deg), p2 colour packed
                           // 0xRRGGBB, p3 punch out 0/1, p4 screen space 0/1 (carried;
                           // the pattern is layer-anchored, the importer reports a
                           // screen-space instance), p5/p6 the position point components;
                           // q0/q1 stretch x/y. kFxStyle mode 20.
    FX_CHECKER2_AM_EXT = 140,
    FX_MOSAIC_AM = 141,  // p0 size 1..500 (layer px), p1/p2 the phase point components.
                         // NOT FX_MOSAIC (the app's own blocky posterize). Dedicated
                         // two-pass (the def's snapped box averages, x then y).
    FX_GLOW_AM = 142,  // p0 radius 0..400, p1 alpha 0..1, p2 hardness 0..1, p3 colour
                       // packed 0xRRGGBB, p4 colour alpha 0..1 (it scales the result's
                       // alpha in blend mode 0, so it cannot ride the packed colour),
                       // p5 blend mode (0 normal, 1 multiply, 2 screen; 1/2 read the
                       // backdrop like FX_DISPLACE_MAP_AM). NOT FX_GLOW (the app's own
                       // bloom). Dedicated two-pass distance scan (FX_INNER_GLOW_AM's
                       // machinery, composite inverted: the glow sits OUTSIDE the layer).
    FX_INNER_BLUR_AM = 143,  // p0 strength 0..1, p1 iterations 1..6. Dedicated pass:
                             // the def's boxblur2 chain (per-pass strength / 4 / iter,
                             // chained iter times) + the un-premultiply composite.
    FX_BOX_BLUR3_AM = 144,  // p0 strength 0..2. Dedicated pass: the def's half-res
                            // symmetric box average (strength / 10), x then y.
    FX_FEATHER_AM = 145,  // p0 strength 0..2, p1 iterations 1..5. Dedicated pass: the
                          // def's quarter-res box averages (strength / 4 / iter, chained
                          // iter times); the output is the layer times the blurred
                          // alpha squared — colour untouched.
    FX_ELECTRIC_EDGES_AM = 146,  // p0 distance, p1 thickness, p2 complexity, p3 disort,
                                 // p4/p5 colours 1/2 packed 0xRRGGBB, p6 alpha;
                                 // q0 glow alpha, q1 seed, q2 evolution, q3 fill,
                                 // q4 mask strength, q5 mask evolution, q6 mask scale;
                                 // r0 mask seed, r1 mask colour packed 0xRRGGBB,
                                 // r2 render contours, r3 contour count, r4 contour
                                 // offset, r5 contour angle, r6 strength (the fixed 0.42
                                 // the def feeds its gaussianblur buffer pass).
                                 // Dedicated pass (blur buffer + the def's main pass;
                                 // the def's `comp` input is sampled but never used).
    FX_ELECTRIC_EDGES_AM_EXT = 147,
    FX_ELECTRIC_EDGES_AM_EXT2 = 148,
    FX_SMOOTH_EDGES_AM = 149,  // p0 strength 0..2, p1 mask to layer 0/1. Dedicated
                               // pass: a gaussianblur buffer (strength through the
                               // gaussianblur kernel) + the def's directional
                               // streak composites, then the alpha smoothstep cut.
    // v176: Queue 15 top batch (hunt round 3, defs-batch8 — the Ultra dump + the
    // TrueMotion conversions agree param-for-param; both passed the schema
    // acceptance test against the corpus's serialized instances).
    FX_GAUSSIAN_BLUR_AM = 150,  // p0 strength 0..2 (AM raw). The standalone host
                                // gaussianblur as its OWN op (it mapped onto the app's
                                // FX_BLUR at strength x 50 until v175): the renderer
                                // computes the def's sigma from the frame diagonal at
                                // the point of application (fxAmGaussianSigma), so the
                                // stored value stays AM's. Keyframed strength rides an
                                // AmFxEval tail from index 7. NOT FX_BLUR (the app's own
                                // panel blur, whose value is its blurriness 0..100).
    FX_DEEP_GLOW2_AM = 151,  // Alight's deepglow2 AND deepglow_enc (one def, two ids —
                             // TrueMotion maps the same source def to both; the corpus
                             // serializes the identical 20-property schema for each).
                             // Dedicated chain (Renderer::fxDeepGlow2Am): the host
                             // gaussianblur of the layer, then the def's composite,
                             // the whole pass list repeated `iter` times. AM units:
                             //   p: [0] strength 0..2 (the blur), [1] oalpha 0..3,
                             //      [2] lowThreshold 0..1, [3] highThreshold 0..1,
                             //      [4] feather 0..10, [5] alpb 0..1, [6] bmode 0..3
                             //      (0 SCREEN, 1 COLOR dodge, 2 LINEAR add, 3 LAYER)
                             //   q: [0] calpha 0..1, [1] hue (raw degrees, def default
                             //      360), [2] satvib x, [3] satvib y, [4] colblend
                             //      packed 0xRRGGBB (its alpha is never read),
                             //      [5] strengthhh -8..8, [6] angleee (raw degrees)
                             //   r: [0] position point packed, [1] stretch point
                             //      packed, [2] OFT point packed, [3] flags:
                             //      overd 1 + GCE 2 + CHROM 4 + cblend 8 + cmode 16 +
                             //      centerChannel 32/64, [4] iter 1..3
                             // Keyframed strength / oalpha ride AmFxEval tails from
                             // index 21. position / stretch / OFT sit at their def
                             // defaults in every corpus instance.
    FX_DEEP_GLOW2_AM_EXT = 152,
    FX_DEEP_GLOW2_AM_EXT2 = 153,
    FX_DROP_SHADOW_AM = 154,  // Alight's dropshadow: the host gaussianblur of the
                              // layer, tinted (colorshadow x alpha) by its alpha,
                              // offset by position x (-1, 1) / 500, the layer laid
                              // over it at alphac (the def's newLayer). Dedicated
                              // pass (Renderer::fxDropShadowAm). AM units:
                              //   p: [0] shadow colour packed 0xRRGGBB, [1] its alpha
                              //      0..1 (it scales the shadow, so it cannot ride the
                              //      packed colour), [2] position point packed,
                              //      [3] strength 0..5 (the blur), [4] alpha 0..5,
                              //      [5] alphac 0..1
    // v177: Queue 15(f) tier-5 batch (hunt round 3, defs-batch8; every def passed
    // the schema acceptance test against the corpus's serialized instances).
    FX_BBMAKER_AM = 155,  // Alight's bbmaker: two wipe bands over the layer in
                          // layer norm — the colour band (colorbg x tex.a, gated by
                          // the startA / endA / angleA / featherA band) under the
                          // picture band (adjStart = (start - 20) / 100, adjEnd =
                          // (120 - start) / 100 along `angle`, feather hardwired
                          // 0 in the def), the sum x tex.a. Dedicated pass.
                          //   p: [0] start (keyframed in all 17 corpus instances),
                          //      [1] angle (raw degrees), [2] colorbg packed
                          //      0xRRGGBB, [3] its alpha, [4] startA, [5] endA,
                          //      [6] angleA (raw degrees)
                          //   q: [0] featherA (raw; the def divides by 100),
                          //      [1] opacity
                          // Keyframed start / angle ride AmFxEval tails from 14.
    FX_BBMAKER_AM_EXT = 156,
    FX_UNSHARP_MASK_AM = 157,  // Alight's unsharpmask: a dedicated chain — the
                               // host gaussianblur of the layer at `strength`
                               // (same-name feed), then the def's composite:
                               // adjColor = base (1 + amount) - blur amount, mixed
                               // in by the luma-difference smoothstep gate.
                               //   p: [0] strength 0..2, [1] amount, [2] threshold,
                               //      [3] showMask 0/1
    FX_NEWSHARPEN_AM = 158,  // Alight's newsharpen ("oldsharpen"): at half res,
                             // a horizontal then vertical streak average of the
                             // picture (velocity = strength / 4 / iter along 0 /
                             // 90 degrees, the boxblur2 kernel form), then
                             // screen(input, (input - streak) x brig) — the whole
                             // pass list repeated `iter` times. Dedicated chain.
                             //   p: [0] strength, [1] whiteonly 0/1,
                             //      [2] bluredonly 0/1, [3] brig, [4] iter
    FX_GRADSHADOW_AM = 159,  // The community blend-generator family (defs-batch8):
                             // a generated fragColor run through the defs' blend
                             // switch, per kind. gradshadow: fragColor = the
                             // layer-norm band (start / end / feather / angle,
                             // smoothstepRev) x tex.a x alphb x color.
                             //   p: [0] start, [1] end, [2] feather (raw; / 100 in
                             //      the shader), [3] angle, [4] color packed,
                             //      [5] its alpha, [6] bright
                             //   q: [0] blendMode, [1] alphb, [2] alphab
                             // Output: mix(fragColor, result, alphab) x tex.a.
    FX_GRADSHADOW_AM_EXT = 160,
    FX_GRADIENTFLOW_AM = 161,  // gradientflow: fragColor = the Perlin-folded
                               // 4-colour flow field in screen norm (NOT centred:
                               // uv = sn / scale, rotated, offset, / (scale x
                               // stretch)); alpha 1. SHORT switch; output
                               // mix(tex, result, alpha) x tex.a.
                               //   p: [0] scale, [1] angle, [2] position packed,
                               //      [3] progress (iTime), [4] speed,
                               //      [5] hardness, [6] blendMode
                               //   q: [0..3] color1..4 packed, [4] FREQ, [5] AMP,
                               //      [6] stretch packed
                               //   r: [0] alpha
                               // autop: progress = PROGRESS + time x SPEED is an
                               // exact linear function of layer time — the
                               // importer bakes it as a synthetic AmFxEval tail
                               // on slot 3 (tails from 21).
    FX_GRADIENTFLOW_AM_EXT = 162,
    FX_GRADIENTFLOW_AM_EXT2 = 163,
    FX_PARTX_AM = 164,  // partx: fragColor = the closed-form starfield in
                        // layer-centred aspect coords (white1 x white2 over the
                        // angle cells), mix(colorbg, colorfg, white). SHORT
                        // switch; output mix(tex, result, alpha) — NO trailing
                        // x tex.a (the def's own inconsistency, kept).
                        //   p: [0] colorfg packed, [1] its alpha,
                        //      [2] colorbg packed, [3] its alpha,
                        //      [4] kNumAngles, [5] kStarDensity, [6] kMotionBlur
                        //   q: [0] EVO (local time), [1] scale, [2] angle,
                        //      [3] positionn packed, [4] stretch packed,
                        //      [5] bright, [6] blendMode
                        //   r: [0] alpha
                        // autop: EVO + time x 0.5 baked as a synthetic tail on
                        // slot 7 (tails from 21), like gradientflow.
    FX_PARTX_AM_EXT = 165,
    FX_PARTX_AM_EXT2 = 166,
    FX_EDGELINE_AM = 167,  // edgeline: fragColor = the Scharr-like 8-tap gradient
                           // (weights 3/10) mapped through color1 / color2 by its
                           // luma (newMode) or the raw (1 - g) silhouette. MINI
                           // switch (modes 0..2, no bright); output
                           // mix(tex, result, alpha).
                           //   p: [0] smoothing, [1] threshold, [2] amp,
                           //      [3] color1 packed, [4] its alpha,
                           //      [5] color2 packed, [6] its alpha
                           //   q: [0] alpha, [1] blendMode, [2] fix 0/1,
                           //      [3] newMode 0/1, [4] invert 0/1
    FX_EDGELINE_AM_EXT = 168,
    FX_LIGHTSWEEP_AM = 169,  // lightsweep: fragColor = the travelling sine band
                             // (sin(r.y + r.x amount - iTime 9) x sharp, cubed
                             // x 0.6) mapped through color1 / color2 by its luma,
                             // alpha = the band. SHORT switch; output
                             // mix(tex, result, alpha) x tex.a.
                             //   p: [0] scale, [1] angle, [2] position packed,
                             //      [3] progress (iTime), [4] speed, [5] amount,
                             //      [6] sharp
                             //   q: [0] stretch packed, [1] color1 packed,
                             //      [2] its alpha, [3] color2 packed,
                             //      [4] its alpha, [5] hardness, [6] blendMode
                             //   r: [0] alpha
                             // Keyframed progress rides a tail from 21 (autop is
                             // false in the corpus; with autop on and a static
                             // PROGRESS the clock bakes like gradientflow's).
    FX_LIGHTSWEEP_AM_EXT = 170,
    FX_LIGHTSWEEP_AM_EXT2 = 171,
    FX_COLORGRAD3_AM = 172,  // 3colorgrad (id com.alightcreative.3colorgrad):
                             // fragColor = the 3-colour ramp along the centred
                             // x (progressa / progressb steps). FULL switch;
                             // output mix(tex, mix(factor, result, alphab) x
                             // tex.a, alpha) x tex.a.
                             //   p: [0] color1 packed, [1] its alpha,
                             //      [2] color2 packed, [3] its alpha,
                             //      [4] color3 packed, [5] its alpha,
                             //      [6] progressa
                             //   q: [0] progressb, [1] offsetx, [2] scale,
                             //      [3] angle, [4] bright, [5] blendMode,
                             //      [6] alpha
                             //   r: [0] alphab
                             // Keyframed angle rides a tail from 21 (slot 10).
    FX_COLORGRAD3_AM_EXT = 173,
    FX_COLORGRAD3_AM_EXT2 = 174,
    FX_DEEP_GLOW_AM = 175,  // Alight's deepglow (v1; the double-sourced def —
                            // Ultra XML + TrueMotion agree): per iteration, the
                            // host gaussianblur of the layer (vblur, `strength`
                            // by name) + a boxblur2 of it (hblur, same-name
                            // strength / iter), then the def's composite: the
                            // blur hue-rotated in YUV, scaled by (rr - 0.5) with
                            // rr = |rrr - screenNorm|, the optional threshold
                            // gate, colour-keyed or raw, over the base.
                            // Dedicated chain (Renderer::fxDeepGlow1Am; one
                            // composite — `iter` only scales the hblur feed).
                            //   p: [0] strength 0..2, [1] rrr (Intensity),
                            //      [2] hue (raw degrees, def default 360),
                            //      [3] oalpha, [4] calpha, [5] alpb,
                            //      [6] color packed 0xRRGGBB
                            //   q: [0] higsha x, [1] higsha y, [2] satvib x,
                            //      [3] satvib y, [4] position packed,
                            //      [5] color alpha, [6] alphaa (shadow strength)
                            //   r: [0] lowThreshold, [1] highThreshold,
                            //      [2] feather, [3] flags (sshadow 1 + cblend 2
                            //      + thrs 4), [4] iter, [5] power (declared by
                            //      the def, never read by its shader — stored
                            //      for the panel, inert in the pass)
    FX_DEEP_GLOW_AM_EXT = 176,
    FX_DEEP_GLOW_AM_EXT2 = 177,
    FX_SPILL_CLEANUP_AM = 178,  // Alight's spill-cleanup: the host gaussianblur
                                // of the layer at `strength` (same-name feed),
                                // two ordered over-compositing streak passes
                                // over it (x then y folds, each composited over
                                // the blur), then the spill composite: the
                                // streak colour scaled by (1 - blur.a) x 2 and
                                // base.a, its YUV luma replaced by the base's,
                                // laid under the base. Dedicated chain.
                                //   p: [0] strength (AM raw; the def's own
                                //      default is 0.02, the corpus runs 0.48..2)
    // v178 (build 136): Alight's motionblur4, ported faithfully from its recovered def
    // (hunt-staging/defs-batch8 — the TrueMotion conversion of AM's own motionblur4.xml:
    // three shader stages gated by the def's animate() script). The stored effect is
    // resolved per frame by Kotlin (MotionBlurEval) into the three passes below, so —
    // like FX_MOTION_BLUR (55) — it must never reach the shaders: fxParseOps skips it
    // and ClipFxLibrary.toNative never emits it.
    FX_MOTION_BLUR4 = 179,  // p0 tune x100 (a keyframed tune rides an AmFxEval tail,
                            // slot 0, raw), p1..p3 usePos / useScale / useAngle (0/1),
                            // p4 samplesMin (def 1..32), p5 samplesMax (def 8..256)
    // The def's three stages as kFxBlur2 modes 10 / 11 / 12 (fxShaderOf), emitted per
    // frame by MotionBlurEval in the def's own group order: position, scale, angle.
    // The slots hold the RESOLVED frame's host quantities (units in MotionBlurEval).
    FX_MB4_POS = 180,   // p0/p1 the picture-space velocity (fractions of the canvas
                        // height per frame), p2 tune (raw, def 0..4), p3 samplesMin,
                        // p4 samplesMax
    FX_MB4_SCALE = 181, // p0 the relative scale change per frame, p1 tune, p2/p3 the
                        // layer pivot (0..100 %), p4 samplesMin, p5 samplesMax
    FX_MB4_ANGLE = 182, // p0 the rotation change per frame (degrees, signed), p1 tune,
                        // p2/p3 the layer pivot (%), p4 samplesMin, p5 samplesMax
    // v179 (build 141): Alight's opticcompesation, ported from the hunt-round-4 def
    // (the ORIGINAL-format def in AlightMotionUltra's UltraMotionEffects, corroborated
    // by TrueMotion's afterz-561 SkSL conversion; round 3 only had an encrypted-copy
    // conversion delegating to a native pass). p: [0] iterations, [1] distortion,
    // [2] strength, [3] separation, [4] blendMode (0 NORMAL / 1 MULTIPLY / 2 SCREEN /
    // 3 LAYER), [5] scale, [6] alpha. q (EXT): [0] position x, [1] position y
    // (AM units, / 500 in the shader), [2] stretch x, [3] stretch y, [4] hardness.
    FX_OPTIC_COMP_AM = 183,
    FX_OPTIC_COMP_AM_EXT = 184,
    // v180 (build 143): Queue 15(j) — the second-wave community ids (hunt round 5:
    // original-format defs from AlightMotionUltra + the TrueMotion conversions in
    // defs-batch8; per-id verdicts in hunt-staging/round5/SOURCES.md). Two of the 22
    // stay parked: ripplelight (the two staged defs are different versions — the
    // Ultra original lacks the instances' fixedUV, the conversion lacks their
    // lightColor) and bccfilmglow (33 shader-read floats exceed the 21-slot op).
    FX_NEGATIVE_AM = 185,   // negative (double-sourced): invert -> luma -> unsharp ->
                            // brightness / contrast -> black/white point remap -> grain.
                            // p: [0] contrast, [1] brightness, [2] blackPoint,
                            // [3] whitePoint, [4] sharpness, [5] grainAmount. kFxColor 27.
    FX_TRITONE_AM = 186,    // tritone (Ultra original; the TrueMotion vs.tritone is a
                            // DIFFERENT effect): the luma ramp shadows -> midtones ->
                            // highlights, mixed back by blend. p: [0] highlights packed,
                            // [1] its alpha, [2] midtones packed, [3] its alpha,
                            // [4] shadows packed, [5] its alpha, [6] blend. kFxColor 28.
    FX_UNMULT_AM = 187,     // unmult (Ultra original): matte alpha from max(rgb) with
                            // threshold / softness / gamma, a 5-tap feather, a choke /
                            // spread cleanup, optional luma-preserving unmultiply.
                            // p: [0] blackLevel (0..255), [1] softness, [2] featherPx,
                            // [3] cleanupPx, [4] edgeGamma, [5] preserveLuma, [6] previewMatte.
                            // q: [0] quality (declared by the def, never read — inert).
                            // Dedicated pass (it rewrites alpha).
    FX_UNMULT_AM_EXT = 188,
    FX_DENOISE_AM = 189,    // denoise (TrueMotion conversion of skexnxxdenoise): the
                            // "Sir Bird" golden-spiral denoise at a hard-coded 512x512
                            // resolution; `strength` is declared but never read.
                            // p: [0] SAMPLES, [1] PIXEL_MULTIPLIER, [2] alpha,
                            // [3] strength (inert). kFxBlur2 mode 13.
    FX_MINIMAX_AM = 190,    // minimaxblur (Ultra original): separable min / max over a
                            // radius, per colour or by one channel. p: [0] operation
                            // (0 min, 1 max), [1] radius (KEYFRAMED: AmFxEval tail),
                            // [2] channel (0..3, 4 colour), [3] direction (0 both,
                            // 1 H only, 2 V only), [4] keepEdge. Dedicated 2-pass chain.
    FX_RGBGLITCH_AM = 191,  // rgbglitch (Ultra original + TrueMotion agree): random
                            // horizontal slices + one channel offset, clocked by
                            // floor(progressa * 36). p: [0] progressa (KEYFRAMED tail),
                            // [1] progressb, [2] progressc, [3] progressd. kFxWarp 28.
    FX_FISHEYE_AM = 192,    // fisheye_antifisheye (Ultra original): tan / atan radial
                            // remap around the centre. p: [0] power (KEYFRAMED tail),
                            // [1] centerX, [2] centerY, [3] aspectMode, [4] alpha.
                            // kFxWarp mode 29.
    FX_VORTEX_BLUR_AM = 193,  // chromaticvortexblur (AM's stock def via Ultra): the
                            // swirl-family rotational blur per channel with centre-
                            // channel offsets. p: [0] strength (KEYFRAMED tail),
                            // [1] radius, [2] colorAberration, [3] centerChannel,
                            // [4] mode, [5] centerPoint packed (def (0, 0) = the
                            // layer centre). kFxBlur2 mode 16.
    FX_REFRACTION_AM = 194,   // refractionwarp (Ultra original + TrueMotion agree):
                            // the cos-field refraction (Shadertoy lineage). p: [0] autop,
                            // [1] PROGRESS, [2] SPEEED, [3] scale, [4] position packed
                            // (def (0, 0), neutral in the shader), [5] intensity
                            // (KEYFRAMED tail), [6] frequency. q: [0] emboss, [1] steps,
                            // [2] angle, [3] delta, [4] gain, [5] reflectionCutOff,
                            // [6] reflectionIntensity. r: [0] speed, [1] speed_x,
                            // [2] speed_y, [3] alpha. kFxWarp mode 30.
    FX_REFRACTION_AM_EXT = 195,
    FX_REFRACTION_AM_EXT2 = 196,
    FX_TWITCH_AM = 197,     // smartMotionBlurShake (Ultra "Twitch"): a stepwise random
                            // shake vector, averaged over N samples. p: [0] amplitude
                            // (KEYFRAMED tail), [1] shakeInterval (s), [2] samples,
                            // [3] angle (deg). kFxBlur2 mode 17.
    FX_TURBULENT2_AM = 198,   // turbulentdisplace2 (TrueMotion afterz 112.xml): AM's
                            // turbulence() field applied per direction mode. p:
                            // [0] intensity (KEYFRAMED tail), [1] evolution,
                            // [2] direction, [3] scale, [4] stretch, [5] seed,
                            // [6] singleAxis. q: [0] screenSpace, [1] octaves,
                            // [2] offs, [3] position packed, [4] parr packed (both
                            // points (0, 0) in the def: neutral offsets). kFxWarp 31.
    FX_TURBULENT2_AM_EXT = 199,
    FX_SHAPEFIELD_AM = 200,   // shapefield (TrueMotion afterz 147.xml): a grid of
                            // shape cells, each cell showing the layer picture tinted
                            // by the colouring mode, layered back to front. p:
                            // [0] pos x, [1] pos y, [2] pos z, [3] density,
                            // [4] evolution (KEYFRAMED tail), [5] seed, [6] scale.
                            // q: [0] shapeSize (KEYFRAMED tail), [1] shapeAngle,
                            // [2] sizeVariance, [3] angleVariance, [4] layerCount,
                            // [5] fade, [6] alpha. r: [0] slack, [1] blendMode,
                            // [2] coloring, [3] color1 packed, [4] color2 packed,
                            // [5] color3 packed, [6] flags (lockLuminance 1 +
                            // lockSaturation 2 + interpHue 4). The colours' alphas
                            // are 0xFF in the def defaults and every corpus
                            // instance, so only rgb rides. kFxStyle mode 21.
    FX_SHAPEFIELD_AM_EXT = 201,
    FX_SHAPEFIELD_AM_EXT2 = 202,
    FX_HALFTONE_DOTS_AM = 203,  // halftone_dots (Ultra original; NOT halftonedots,
                            // native 71): the sin-product dot screen in pixel space.
                            // p: [0] dotsMode, [1] dotsFrequency, [2] dotsAngle,
                            // [3] dotsRelWidth, [4] dotsSharpness, [5] dotsLighten,
                            // [6] smoothSource. q: [0] color1 packed, [1] color0
                            // packed, [2] shiftX, [3] shiftY, [4] alpha.
                            // kFxStyle mode 22.
    FX_HALFTONE_DOTS_AM_EXT = 204,
    FX_CC_GLASS_AM = 205,   // cc_glass (Ultra "Glass Surface"): bump gradient from the
                            // layer itself, refraction offset, Blinn-Phong light.
                            // p: [0] property, [1] softness, [2] height,
                            // [3] displacement, [4] lightType, [5] lightIntensity,
                            // [6] lightColor packed (its alpha is never read).
                            // q: [0] lightHeight, [1] lightPosition x, [2] lightPosition
                            // y (the def's point is in NORM units, default (0.5, 0.5) —
                            // two raw slots, not a packed AM point), [3] lightDirection,
                            // [4] ambient, [5] diffuse, [6] specular.
                            // r: [0] roughness, [1] metal. kFxWarp mode 32.
    FX_CC_GLASS_AM_EXT = 206,
    FX_CC_GLASS_AM_EXT2 = 207,
    FX_BOKEH_AM = 208,      // cinematic_bokeh (Ultra "Bokeh Blur"): golden-spiral
                            // bokeh with polygon shape modulation + highlight
                            // weighting. p: [0] blurShape, [1] blurWidth,
                            // [2] blurHeight, [3] blurRadius (KEYFRAMED tail),
                            // [4] highlightThreshold, [5] highlightGain, [6] quality.
                            // kFxBlur2 mode 14.
    FX_LENSBLUR_OBS_AM = 209,  // lensblurobs (Ultra "Bokeh Lens Blur"): the pow-4
                            // weighted spiral bokeh. p: [0] ITERATIONS,
                            // [1] progressa (KEYFRAMED tail), [2] RADIUS,
                            // [3] bright, [4] alpha, [5] blendMode. kFxBlur2 mode 15.
    FX_SOLID_WIPE_AM = 210,   // solid_color_wipe (Ultra original): the wipe2 mask
                            // along the angle over the layer, filled with the solid
                            // colour through the solidcolor blend switch. p:
                            // [0] fillColor packed, [1] its alpha, [2] alpha,
                            // [3] blendMode, [4] start, [5] end, [6] angle.
                            // q: [0] feather. kFxWarp mode 33.
    FX_SOLID_WIPE_AM_EXT = 211,
    FX_SOFTBLOOM_AM = 212,  // softblooom (TrueMotion legacy amz_417): threshold
                            // extract at half res, the def's OWN incremental-gaussian
                            // blur (GPU Gems paired taps; adjSigma = kernelSize /
                            // 2.14596602, kernelSize = strength / 2 x 1.14 / 4 / 3 x
                            // the canvas diagonal — fxSoftbloomAmKernel), composite.
                            // (Open item 2 of the 15(j) banked findings, resolved for
                            // the DEF's kernel over the WIP header's host-gaussian
                            // sketch: the staged def IS Light Glow's blurInternal
                            // with stretch / aspect terms, so the chain mirrors
                            // fxLightGlowAm's shape.)
                            // p: [0] strength, [1] intensity, [2] threshold,
                            // [3] glowStretch x, [4] glowStretch y, [5] galpha,
                            // [6] alpha. Dedicated chain (Renderer::fxSoftbloomAm);
                            // the def gates the whole effect on strength > 0.0001.
    FX_LENSD_AM = 213,      // lensd (Ultra "Lens Distortion+"): a chromabbr pass
                            // (its own Ultra def; amp / new forwarded by name,
                            // rgbsplit / alphaa at their defaults) into xdist, then
                            // the channel-spread average over scaled samples of it.
                            // The whole chain repeats `iter` times. p: [0] offs,
                            // [1] amp (KEYFRAMED tail), [2] strength, [3] blurMax,
                            // [4] aberrationMax, [5] numIters, [6] bright.
                            // q: [0] alpha, [1] new, [2] iter. Dedicated chain.
    FX_LENSD_AM_EXT = 214,
    // v181 (build 150): Alight's bccfilmglow — the last corpus effect with a
    // complete staged def (the round5 Ultra original "Film Glow"; the TrueMotion
    // conversion in defs-batch8 agrees param-for-param and formula-for-formula).
    // It stayed parked through build 147 because its shader reads 33 floats,
    // more than the 21 slots p/q/r hold; the EXT3 / EXT4 groups below are the
    // op model's third and fourth extension banks (s / t), folded by fxParseOps
    // only in the full chain owner -> EXT -> EXT2 -> EXT3 -> EXT4.
    // A five-pass chain (Renderer::fxBccFilmGlowAm): soft-knee threshold
    // extract at half res, inner separable gaussian (H then V), outer gaussian
    // (H only — the def has no outer V pass), then the composite: highlight
    // glow, colour preservation, saturation / warmth / tint, halation,
    // diffusion, soft clip, the blend switch, and the film grade tail
    // (saturation / contrast / warmth / fade / grain / dither / global mix).
    // All values in AM's own units:
    //   p: [0] glowAmount, [1] glowRadius, [2] glowSoftness, [3] glowSpread,
    //      [4] threshold, [5] softKnee, [6] threshGamma
    //   q: [0] hlGlowStr, [1] hlThreshold, [2] hlSaturation, [3] innerWeight,
    //      [4] outerWeight, [5] tailWeight, [6] glowTint packed 0xRRGGBB (the
    //      def declares the colour alpha="false" and its shader reads .rgb
    //      only, so the alpha never rides)
    //   r: [0] glowTintStr, [1] glowWarmth, [2] glowSatBoost,
    //      [3] colorPreserve, [4] halStr, [5] halColor packed 0xRRGGBB
    //      (same alpha rule), [6] diffAmount
    //   s: [0] diffHighlights, [1] filmGrain, [2] filmFade,
    //      [3] filmSaturation, [4] filmContrast, [5] filmWarmth,
    //      [6] softClipKnee
    //   t: [0] blendMode (1 Add, 2 Screen, 3 Soft Light, 4 Lighten),
    //      [1] quality (1..3), [2] glowOnly 0/1, [3] ditherAmt,
    //      [4] globalMix, [5..6] unused
    // The one corpus instance (omniframe-hardtrekk-hero) is fully static, so
    // no AmFxEval tail.
    FX_BCC_FILM_GLOW_AM = 215,
    FX_BCC_FILM_GLOW_AM_EXT = 216,
    FX_BCC_FILM_GLOW_AM_EXT2 = 217,
    FX_BCC_FILM_GLOW_AM_EXT3 = 218,
    FX_BCC_FILM_GLOW_AM_EXT4 = 219,
    FX_MAX = FX_COPY_BG
};

// Ops per layer (ClipFxLibrary.MAX_EFFECTS in ClipFx.kt — one bound, two languages). 32 since
// build 130: the largest stack in the 93-file Alight corpus is 29, and the old 12 silently ate
// real effects off such layers (Queue 14h). The cost is this array alone (32 x sizeof(FxOp)
// per layer draw) plus the matching JNI staging buffer in render_jni.cpp.
constexpr int kFxMaxOps = 32;
constexpr int kFxStride = 8;  // floats per effect in the JNI array: type, p0..p6

struct FxOp {
    int type = FX_NONE;
    float p[7] = {0, 0, 0, 0, 0, 0, 0};
    float q[7] = {0, 0, 0, 0, 0, 0, 0};  // extended params, folded in by fxParseOps from an EXT group
    float r[7] = {0, 0, 0, 0, 0, 0, 0};  // second extension, folded from an EXT2 group (v173: extrude tier)
    float s[7] = {0, 0, 0, 0, 0, 0, 0};  // third extension, folded from an EXT3 group (v181: bccfilmglow)
    float t[7] = {0, 0, 0, 0, 0, 0, 0};  // fourth extension, folded from an EXT4 group (v181: bccfilmglow)
};

// Reads `n` floats (groups of kFxStride) into `out` (room for kFxMaxOps). Unknown types are skipped.
int fxParseOps(const float* flat, int n, FxOp* out);

// Which of the two fx targets an applyFx / applyTexMask pass stages its source picture into.
// The pass chain ping-pongs between the targets, and a pass's INPUT can itself be a previous
// pass's result — since build 121 an overlay layer's own masks run as a second applyFx chained
// after its effect pass. When the input is fx target 0, staging into target 0 too would clear
// and then sample the very texture being rendered into (a GL feedback loop): the picture comes
// out erased. The Graph bars hit exactly this (their only op, Edge Glow, returns the picture
// to the target it started in). Start in target 1 whenever the input is target 0.
inline int fxFirstTarget(uint32_t inputTex, uint32_t tex0) {
    return (tex0 != 0 && inputTex == tex0) ? 1 : 0;
}

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
// The FX_BLUR sigma (in the current frame's pixels) of a Glow Scan smoothing value
// (%, AM strength x 100): the host gaussianblur formula (fxAmGaussianSigma) at the
// frame diagonal. (Until v175 this went through the app blur's value at the flat
// x50 calibration; the gaussianblur def's own kernel replaces it.)
float fxGlowScanBlurSigma(float smoothingPct, float previewDiagPx);

// ---- v163: Alight's next tier (the maths of the recovered definitions; the GLSL twins live in
// fx_shaders.h modes 12..14 / 17 and in renderer_effects.cpp's dedicated passes) ----
// CPU reference of Luma Key: the alpha multiplier of a pixel whose PREMULTIPLIED r / g / b are given
// (AM reads texColor.rgb as it is). Thresholds / feather are 0..1.
float fxLumaKeyAlpha(float r, float g, float b, float low, float high, float feather, bool invert, bool weighted);
// CPU reference of Replace Color: straight r / g / b in and out (0..1); the old / new colours are
// straight too (their alpha is 1: AM declares them alpha="false"); threshold / feather / alpha are
// AM units (0..4 / 0..2 / 0..1). The YUV distance weights chroma x4 and luma x0.5, like AM.
void fxReplaceColorMap(float r, float g, float b, float oldR, float oldG, float oldB,
                       float newR, float newG, float newB, float threshold, float feather,
                       float alpha, bool lockLuminance, float* outR, float* outG, float* outB);
// CPU reference of Four Color Gradient at layer-norm point (stX, stY): cols are the four straight
// colours (r, g, b triples), offs the four AM points (x, y pairs); tex is the layer pixel
// (premultiplied). The weighted average divides by the sum of the UNpowered smooth weights, like AM.
void fxFourColorMap(float stX, float stY, const float cols[12], const float offs[8], float blend,
                    float alpha, int blendMode, float tr, float tg, float tb, float ta,
                    float* outR, float* outG, float* outB, float* outA);
// Ashima simplex noise (the snoise of AM's turbulentdisplace shader, ported line by line) and AM's
// turbulence sum over it: sum of amp * |snoise(st, evolution)|, amp halving, st doubling + parallax.
float fxSnoise3(float x, float y, float z);
float fxTurbulence(float stX, float stY, int octaves, float parX, float parY, float evolution);
// CPU reference of Stretch Segment: the sample position for a fragment at (uvX, uvY) (norm, centre
// (0.5, 0.5) like every warp here). angleDeg / stretch / offset / smooth are AM units (smooth 0..1).
void fxStretchSegmentUv(float uvX, float uvY, float angleDeg, float stretch, float offset, float smooth,
                        float* outX, float* outY);
// CPU reference of Swirl (swirl4 / swirl3): the sample position for a fragment at (uvX, uvY);
// (cX, cY) is the centre in norm units, aspect = picture width / height, strength -0.5..0.5,
// radius 0..0.8 (both in the aspect-corrected space, like AM).
void fxSwirlUv(float uvX, float uvY, float cX, float cY, float strength, float radius, int version,
               float aspect, float* outX, float* outY);
// CPU reference of Halftone Dots' sample position and cell distances: the fragment at (uvX, uvY)
// samples at (*outX, *outY); *absDistX / *absDistY are the folded in-cell distances (0..0.5) the
// colour decision measures. amount / angleDeg / phase are AM units, aspect = width / height.
void fxHalftoneDotsUv(float uvX, float uvY, float amount, float angleDeg, float phase, float aspect,
                      float* outX, float* outY, float* absDistX, float* absDistY);
// CPU reference of Halftone Dots' colour decision: samp is the displaced sample (premultiplied),
// c1 / c2 the two straight colours, texA the fragment's own alpha (AM scales the result by it).
void fxHalftoneDotsMap(float sampR, float sampG, float sampB, float sampA, float absDistX, float absDistY,
                       float strength, const float c1[3], const float c2[3], float texA,
                       float* outR, float* outG, float* outB, float* outA);
// CPU reference of Pixelate's block centre: the fragment at (uvX, uvY) samples at (*outX, *outY);
// *distCenter is the vignette's radial factor. size / angle are AM units, stretch / offset AM
// values, picW / picH the picture size in pixels (AM's acScreenSize).
void fxPixelateCentre(float uvX, float uvY, float size, float stretchX, float stretchY, float angleDeg,
                      bool screenSpace, float offX, float offY, float picW, float picH,
                      float* outX, float* outY, float* distCenter);
// ---- v164: Alight's parked tier (hunt round 2; the GLSL twins live in fx_shaders.h modes
// 18..20 / blur2 8 / warp 15..17 / style 14 and in renderer_effects.cpp's dedicated passes) ----
// CPU reference of Chroma Key (AM's chromakey): the pixel is PREMULTIPLIED (pr..pa), the key
// colour straight (kr..kb, its alpha is 1); threshold / feather are AM raw (0..1). [border] is
// AM's half-texel edge rule (the fragment sits within half a texel of the layer edge). The
// defringe subtraction happens on the premultiplied pixel BEFORE the alpha is applied, like AM.
void fxChromaKeyAmMap(float pr, float pg, float pb, float pa, float kr, float kg, float kb,
                      float threshold, float feather, bool defringe, bool invert, bool border,
                      float* outR, float* outG, float* outB, float* outA);
// CPU reference of Color Balance (AM's colorbalance): the pixel is PREMULTIPLIED in and out
// (AM computes on the sampled colour; the colour program's twin computes on c.rgb too and
// un-divides by the alpha at the end, so the two agree for every alpha). The nine band values
// are AM raw (-100..100, clamped there), fixLum restores the input Y afterwards.
void fxColorBalanceAmMap(float r, float g, float b, const float bands[9], bool fixLum,
                         float* outR, float* outG, float* outB);
// CPU reference of Color Temperature (AM's colortemperature): the Kelvin->RGB gain of
// [kelvin] (1000..40000), AM's own polynomial fit, line by line.
void fxColorTempAmGain(float kelvin, float* outR, float* outG, float* outB);
// CPU reference of Color Tint (AM's colortint): the pixel is PREMULTIPLIED (pr..pa, like AM
// samples it); hueDeg / strength are AM raw — the chroma mix factor is strength * pa, like AM.
// out is premultiplied too.
void fxColorTintAmMap(float pr, float pg, float pb, float pa, float hueDeg, float strength,
                      float* outR, float* outG, float* outB, float* outA);
// CPU reference of Offset (AM's offset): S is the pixel, F the wrap-shifted sample (both
// premultiplied); featherAmount is AM's seam factor (featherAmtX * featherAmtY, precomputed by
// fxOffsetFeather below), maskFactor is the pixel's own alpha (mask) or 1 inside the layer.
void fxOffsetAmMap(float sr, float sg, float sb, float sa, float fr, float fg, float fb, float fa,
                   float featherAmt, float maskFactor,
                   float* outR, float* outG, float* outB, float* outA);
// AM's offset seam factor at layer-norm (uvX, uvY) for an offset (offX, offY) (AM units) and a
// feather (0..1): 1 away from the wrap seam, dipping toward 0 at it.
float fxOffsetFeather(float uvX, float uvY, float offX, float offY, float feather);
// CPU reference of Radial Wipe (AM's radialwipe): the visibility multiplier (0..1) of the
// fragment whose aspect-corrected, centre-relative position is (stX, stY) (y DOWN, like AM's
// flipped space). progress / feather / offset are degrees, direction 0/1/2, repeat >= 1.
float fxRadialWipeAmPp(float stX, float stY, float progress, float feather, int direction,
                       float repeat, float offsetDeg);
// CPU reference of Find Edges (AM's findedges): g is the Scharr gradient magnitude over the
// eight neighbours' luma (premultiplied, like AM samples), threshold AM raw (0..1).
float fxFindEdgesAmG(const float luma8[8], float threshold);
// CPU reference of Hex Shift (AM's hexshift): the sample position for a fragment at (uvX, uvY);
// size / stretch / angleDeg / seed are AM units, aspect = picture width / height (the layer's;
// AM's screen-space grid anchoring is the layer's own here, like Turbulent's). strength scales
// the per-cell random displacement; the sample wraps mirrored, like AM.
void fxHexShiftAmUv(float uvX, float uvY, float size, float stretchX, float stretchY,
                    float angleDeg, float seed, float strength, float posX, float posY,
                    float aspect, float* outX, float* outY);
// CPU reference of Soft Glow (AM's softglow), the layer-local branch (outsideAlpha = 0): tex is
// the pixel, blur its blurred copy (both premultiplied); brightness / contrast / highlights /
// blend / alpha are AM raw (0..1-ish), the colour is straight r / g / b. A fully transparent
// pixel stays transparent (AM divides by the alpha there; the result is invisible either way).
void fxSoftGlowAmMap(float tr, float tg, float tb, float ta, float br, float bg, float bb, float ba,
                     float brightness, float contrast, float highlights,
                     float colR, float colG, float colB, float blend, float alpha,
                     float* outR, float* outG, float* outB, float* outA);
// CPU reference of Fill Behind (AM's fillbehind), the colour pipeline of the background: the
// blurred copy's straight colour adjusted (saturation / tint / gamma; AM's brightness is 0 in
// the only corpus instance and has no slot). blurA is the blurred copy's alpha.
void fxFillBehindAmBg(float br, float bg, float bb, float blurA, float saturation,
                      float tintR, float tintG, float tintB, float tintStrength, float gamma,
                      float* outR, float* outG, float* outB, float* outA);
// CPU reference of Dark Glow (AM's darkglow def, build 120), its two pixel stages (the blur
// between them is the shared machinery). fxDarkGlowExtract is the def's pass 0: a pixel whose
// luma is below the threshold is kept, mixed toward the colour by blend; anything brighter
// becomes transparent. fxDarkGlowComposite is the def's final composite: the blurred extract's
// straight colour multiplied by the base's straight colour, laid over the base at
// min(base.a, extract.a) * alpha. All inputs premultiplied, params AM raw (0..1).
void fxDarkGlowExtract(float r, float g, float b, float a, float threshold,
                       float colR, float colG, float colB, float blend,
                       float* outR, float* outG, float* outB, float* outA);
void fxDarkGlowComposite(float baseR, float baseG, float baseB, float baseA,
                         float blurR, float blurG, float blurB, float blurA, float alpha,
                         float* outR, float* outG, float* outB, float* outA);
// The def's blur kernel as a full-resolution sigma in px: AM blurs at half resolution with
// numBlurPixelsPerSide = strength / 2 * 1.14 / 4 / 3 * length(preview size) and
// sigma = numBlurPixelsPerSide / 2.14596602 in half-res px, i.e. twice that at full res.
float fxDarkGlowSigma(float strength, float previewDiagPx);
// The host gaussianblur kernel of Alight's gaussianblur def (hunt round 3,
// defs-batch8 — the Ultra dump's stock copy; darkglow's def carries the identical
// kernel): the full-resolution sigma in px for an AM `strength` (0..2) at a render
// surface whose diagonal is [previewDiagPx] (AM's length(acPreviewSize); in this
// engine the renderer's current frame, width() x height(), the fxDarkGlowSigma
// precedent). AM runs the kernel at half resolution, which softens a touch more
// than the same sigma at full res — a known second-order difference, as in build
// 120's darkglow port. This replaces the flat x50 calibration the app used until
// v175 (strength x 50 onto the app blur's value: ~2x too weak at 1080p, only
// accidentally right near 480p).
float fxAmGaussianSigma(float strength, float previewDiagPx);
// CPU reference of Deep Glow 2's composite (AM's deepglow2 def, hunt round 3; the
// GLSL twin is Renderer::fxDeepGlow2Am's program): [tex] is the layer pixel and
// [blurMid] the blurred layer at the glow position (both PREMULTIPLIED, 0..1+);
// [blurLow] / [blurHigh] are the blurred layer at the chromatic -/+ offsets (only
// read when CHROM is on). The remaining params are the op's, AM raw: the sat /
// vibrance point, the hue in raw degrees, the key colour's straight r / g / b, the
// flags (overd 1 + GCE 2 + CHROM 4 + cblend 8 + cmode 16 + centerChannel 32/64)
// and the blend mode. The un-premultiply of the glow divides by max(a, 0.0001)
// (the edgeglow-comp guard precedent; invisible where a ~ 0). The threshold gate
// keeps the def's own condition verbatim: it applies only when lowThreshold >
// 0.001 or highThreshold < 0.001. At strength <= 0.00001 the def outputs the
// input itself; overd then multiplies the output by the input's alpha.
void fxDeepGlow2Pixel(float texR, float texG, float texB, float texA,
                      float blurR, float blurG, float blurB, float blurA,
                      float blurLowR, float blurLowG, float blurLowB,
                      float blurHighR, float blurHighG, float blurHighB,
                      float strength, float oalpha, float lowThreshold, float highThreshold,
                      float feather, float calpha, float hue, float satX, float satY,
                      float colR, float colG, float colB, float alpb,
                      int flags, int bmode,
                      float* outR, float* outG, float* outB, float* outA);
// CPU reference of Drop Shadow's composite (AM's dropshadow def, hunt round 3):
// [base] is the layer pixel (premultiplied), [blurA] the blurred layer's alpha at
// the offset position; the shadow colour is straight r / g / b / a. The output is
// the def's newLayer(shadow, base x alphac): shadow = (colour x alpha) x blurA.
void fxDropShadowPixel(float baseR, float baseG, float baseB, float baseA, float blurA,
                       float colR, float colG, float colB, float colA,
                       float alpha, float alphac,
                       float* outR, float* outG, float* outB, float* outA);
// CPU reference of Edge Glow (AM's edgeglow def): fxEdgeGlowAmEdge is pass 1's output
// stage — the Scharr magnitude g of the 8 neighbours' premultiplied lumas (luma8 order:
// nw, n, ne, w, e, sw, s, se — fxFindEdgesAmG's kernel, which findedges-edgeglow's def
// shares verbatim) applied to the centre pixel: (g * texColor) normally, or
// ((1 - g) * a) per channel when inverted. fxEdgeGlowAmComposite is the def's final pass
// over the base pixel and the blurred edge pixel (both premultiplied): un-premultiply,
// pow(spread) on the glow, pow(1 / gamma) + a YUV brightness shift on the base, then
// base' = mix(base, base * tintColor, tint) plus mix(glow, fillColor * lum, factor) with
// factor = max(saturate(blend) * (1 - lum), blend - 1), lum the pow'd glow's luma. All
// params AM raw. (The blur between the passes is the shared machinery.)
void fxEdgeGlowAmEdge(const float luma8[8], float cR, float cG, float cB, float cA,
                      float threshold, bool invert,
                      float* outR, float* outG, float* outB, float* outA);
void fxEdgeGlowAmComposite(float baseR, float baseG, float baseB, float baseA,
                           float glowR, float glowG, float glowB, float glowA,
                           float spread, float gamma, float brightness,
                           float fillR, float fillG, float fillB, float blend,
                           float tintR, float tintG, float tintB, float tint,
                           float* outR, float* outG, float* outB, float* outA);
// AM's gaussianblur strength for the edge buffer, as a sigma in the current
// frame's pixels: the host gaussianblur formula (fxAmGaussianSigma) at the frame
// diagonal. Strength 0 gives sigma 0 — the def composites the unblurred edge
// buffer then. (Until v175 this was fxEdgeGlowAmBlurOp, the flat x50 calibration
// onto the app blur's value.)
float fxEdgeGlowAmBlurSigma(float strength, float previewDiagPx);
// Spin Blur (AM's spinblur2) has no scalar CPU reference: its tap count depends on the pixel's
// radius, so the host test pins fxShaderOf / fxIsIdentity and the importer pins only.

// ---- v165: Alight's tier 3 (hunt round 3; the GLSL twins live in fx_shaders.h warp modes
// 18..26 / colour modes 21..24 / blur2 mode 9 / style modes 15..18 and in
// renderer_effects.cpp's dedicated passes) ----
// CPU reference of Wave Warp (AM's wavewarp2): the sample position for a fragment at
// (uvX, uvY) (layer norm, like every warp here). All params are AM units (angles degrees).
void fxWaveWarpAmUv(float uvX, float uvY, float phase, float a1d, float m1, float m2, float a2d,
                    float damping, float dampingSpace, float dampingOrigin, float* outX, float* outY);
// CPU reference of Circular Ripple (AM's circularripple2 = circularripple): the sample
// position for a fragment at (uvX, uvY); (cX, cY) is the centre point in AM units,
// aspectYX = picture height / width (AM corrects with the screen's; the layer's is the
// stand-in here, identical for full-frame layers). strength / radius / frequency / feather /
// phase are AM units.
void fxCircularRippleAmUv(float uvX, float uvY, float strength, float radius, float frequency,
                          float feather, float phase, float cX, float cY, float aspectYX,
                          float* outX, float* outY);
// CPU reference of Stretch (AM's stretch2 / stretch): the sample position for a fragment at
// (uvX, uvY); scale / angleDeg are AM units. The two versions share this map and differ only
// in the contentOnly compose (fxStretchAmMap).
void fxStretchAmUv(float uvX, float uvY, float scale, float angleDeg, float* outX, float* outY);
// The contentOnly compose of Stretch: S is the displaced sample, T the fragment's own pixel
// (both premultiplied). The sample is masked by T's alpha first; v2 keeps the masked
// sample alone, v1 composites T under it. Without contentOnly the output is S itself
// (no twin needed).
void fxStretchAmMap(float sr, float sg, float sb, float sa, float tr, float tg, float tb, float ta,
                    int version, float* outR, float* outG, float* outB, float* outA);
// CPU reference of Squeeze (AM's squeeze): the sample position for a fragment at (uvX, uvY);
// strength is AM raw (-2..2). The def's axis rule: x is divided by a factor driven by |y|
// (with -strength), y by a factor driven by |x| (with +strength).
void fxSqueezeAmUv(float uvX, float uvY, float strength, float* outX, float* outY);
// McGuire value noise + fbm, AM's fractalwarp4 / dots2 helper set (line by line).
float fxValueNoise(float stX, float stY);
float fxValueFbm(float stX, float stY, float parX, float parY, int octaves, float intensity);
// CPU reference of Fractal Warp (AM's fractalwarp4): the sample position for a fragment at
// (uvX, uvY); offs / parallax are AM points (x, y), aspect = picture width / height.
void fxFractalWarpAmUv(float uvX, float uvY, float mag, float scale, float intensity, int octaves,
                       float offX, float offY, float parX, float parY, float aspect,
                       float* outX, float* outY);
// CPU reference of Stripes (AM's stripes): the band value (0..1) at a fragment; st is the
// def's layer space, ((uv flipped to top-down) * picture px - centre px) / |picture px| —
// computed here from (uvX, uvY) and the picture size. amount / phase / width / smoothing /
// angleDeg are AM units.
float fxStripesAmBand(float uvX, float uvY, float picW, float picH, float amount, float width,
                      float angleDeg, float phase, float smoothing);
// Stripes' compose: T is the fragment's pixel (premultiplied), the colour is straight
// (its alpha is 1 in AM); punchout multiplies T by (1 - band) instead.
void fxStripesAmMap(float tr, float tg, float tb, float ta, float colR, float colG, float colB,
                    float band, bool punchout, float* outR, float* outG, float* outB, float* outA);
// CPU reference of Turbulent Displace 3 (AM's turbulentdisplace3): the sample position for a
// fragment at (uvX, uvY) and whether it lands inside the layer (AM multiplies the sample by
// the [0,1]^2 step). direction is AM's selector (0 X, 1 Y, 2 XY, 3 R, 4 φr, 5 φ); offs /
// position / parallax are AM points; aspect = picture width / height.
void fxTurbulentDisplace3AmUv(float uvX, float uvY, float intensity, float scale, float evolution,
                              float seed, int direction, int octaves, float offs,
                              float parX, float parY, float posX, float posY,
                              float stretchX, float stretchY, bool singleAxis, float aspect,
                              float* outX, float* outY, bool* inside);
// CPU reference of Roughen Edges (AM's roughenedges): the displacement p of a fragment is
// returned via *outP and its displaced sample position via (*outX, *outY) — the effect's
// output is the fragment's own pixel times the displaced sample's alpha (the renderer does
// that multiply; the twin pins the geometry). strength / detail / evolution / seed are AM
// units; aspect = picture width / height.
void fxRoughenEdgesAmUv(float uvX, float uvY, float strength, float detail, float evolution,
                        float seed, int octaves, float aspect,
                        float* outX, float* outY, float* outP);
// Streaks (AM's streaks): the tap velocity (norm units) and sample count for a strength /
// angle; texelX / texelY are the picture texel size, aspectYX = picture height / width (AM
// corrects with the screen's; the layer's is the stand-in). One combine step and the
// per-channel finish (mode 3 divides by the sample count), AM's per-channel modes.
void fxStreaksAmVelocity(float strength, float angleDeg, float texelX, float texelY,
                         float aspectYX, float* outVx, float* outVy, int* outSamples);
// Motion Blur 4 (AM's motionblur4; kFxBlur2 modes 10..12 are the GLSL twins): the def's
// sample-count law, shared by its three stages — the count is the trail's length in
// texels, taken as an int after clamping: int(clamp(speed, samplesMin + 0.01,
// samplesMax + 0.01)).
int fxMb4Samples(float speedTexels, float samplesMin, float samplesMax);
// The scale stage's speed: |scaleVel * tune| * distPx / 2 (distPx = the fragment's
// distance from the pivot in texels). motionblur4's own stage-1 formula omits the
// texel factor its sibling stages carry; moblurplus's hybrid stage (same def family,
// defs-batch8) computes the count exactly this way, which is the restored law.
float fxMb4ScaleSpeed(float scaleVel, float tune, float distPx);
// The angle stage's speed at radiusPx texels: the arc length of |angularVelDeg * tune|.
float fxMb4AngleSpeed(float angularVelDeg, float tune, float radiusPx);
float fxStreaksAmCombine(float acc, float tap, int mode);
float fxStreaksAmFinish(float acc, int mode, int nSamples);
// CPU reference of Colour Hot (AM's colorhot): the pixel is PREMULTIPLIED in and out (AM
// computes its luminance on the sampled colour). The two hue-discs are (hue deg, saturation
// 0..1, bias) triples.
void fxColorHotAmMap(float r, float g, float b, const float colorDisc[3], const float tintDisc[3],
                     float* outR, float* outG, float* outB);
// CPU reference of Colorize (AM's colorize): the pixel is PREMULTIPLIED in and out; the
// chroma mix factor is amount * a, like AM (and like Color Tint's). hueDeg is AM raw.
void fxColorizeAmMap(float pr, float pg, float pb, float pa, float hueDeg, float amount,
                     float* outR, float* outG, float* outB, float* outA);
// CPU reference of Highlights & Shadows (AM's highlightshadow, GPUImage's formula): the
// pixel is PREMULTIPLIED in and out (AM divides the sampled colour by its own luminance;
// a black pixel maps to black — the division is guarded, the mixes still apply).
void fxHighlightShadowAmMap(float r, float g, float b, float shadows, float highlights,
                            float* outR, float* outG, float* outB);
// CPU reference of Spot Colour (AM's spotcolor2, shader group 0): the pixel is PREMULTIPLIED
// in and out; the spot colour is straight (its alpha is 1: AM declares it alpha="false").
// threshold / feather / saturation / choke are AM raw — note the desaturated colour keeps
// the CHOKED chroma times saturation / 2, like the def.
void fxSpotColorAmMap(float pr, float pg, float pb, float pa, float spotR, float spotG, float spotB,
                      float threshold, float feather, float saturation, float choke,
                      float* outR, float* outG, float* outB, float* outA);
// CPU reference of Dissolve (AM's dissolve): the per-pixel hash r of a fragment — the
// fragment is kept when amount <= r. (uvX, uvY) is the layer norm (y up, like every warp
// here; AM builds its grid from the top-down pixel position — the hash input here is
// floor((uv - 0.5) * picture px) / picture px, the same values). seed is AM raw.
float fxDissolveAmHash(float uvX, float uvY, float picW, float picH, float seed);
// CPU reference of Simple Stars (AM's simplestars2): the star field value p at a fragment
// (before the colour / blend compose, which fxSimpleStarsAmMap pins).
float fxSimpleStarsAmField(float uvX, float uvY, float scale, float starSize, float starFeather,
                           float starCount, float parX, float parY, float parZ, float aspect);
// Simple Stars' compose: T is the fragment's pixel (premultiplied), adj the star colour
// (premultiplied, fxSimpleStarsAmAdj), blendMode AM's (0..3), alpha the final mix.
void fxSimpleStarsAmAdj(float colR, float colG, float colB, float p, float brightness,
                        float* outR, float* outG, float* outB, float* outA);
void fxSimpleStarsAmMap(float tr, float tg, float tb, float ta,
                        float ar, float ag, float ab, float aa, float p,
                        int blendMode, float alpha,
                        float* outR, float* outG, float* outB, float* outA);
// CPU reference of Dots (AM's dots2): the dot field at a fragment — *intensity is the disc
// falloff (0..1), *colorPct the per-dot colour mix. All params AM units; aspect = picture
// width / height; (posX, posY) is the position point.
void fxDotsAmField(float uvX, float uvY, float scale, float phase, float radius, float feather,
                   float density, float seed, float slack, float posX, float posY, float aspect,
                   float* intensity, float* colorPct);
// Dots' compose: T is the fragment's pixel (premultiplied); rcolor is the dot colour times
// the field intensity times T's alpha (premultiplied, built by the caller from
// fxDotsAmField); over = T * fill. blendMode AM's (0..2), alpha the final mix.
void fxDotsAmMap(float tr, float tg, float tb, float ta, float rr, float rg, float rb, float ra,
                 float fill, int blendMode, float alpha,
                 float* outR, float* outG, float* outB, float* outA);
// CPU reference of Ridges (AM's ridges2 / ridges): the field value p at a fragment (before
// the colour mix, which is mix(colour1, colour2, p) * tex.a over the pixel). version selects
// the stretch rule; offs / parallax are AM points; aspect = picture width / height.
float fxRidgesAmField(float uvX, float uvY, float intensity, float scale, float evolution,
                      float seed, int octaves, float offX, float offY, float parX, float parY,
                      float stretchX, float stretchY, int version, float aspect);
// CPU reference of Bend Warp (AM's bendwarp): the sample position for a fragment at
// (uvX, uvY) (layer norm); *inside is AM's [0,1]^2 step on the bent position (the
// before-start freeze region reports inside with the unbent position). amountDeg / offset /
// highLimit / lowLimit are AM units; aspect = picture width / height.
void fxBendWarpAmUv(float uvX, float uvY, float amountDeg, float offset, int originType,
                    int oppositeType, float highLimit, float lowLimit, float aspect,
                    float* outX, float* outY, bool* inside);
// CPU references of Displace Map (AM's displacemap3 = displacemap2): the channel pick
// (type 0 luma / 1 R / 2 G / 3 B) on a PREMULTIPLIED pixel, the from-centre / invert shaping,
// the displaced screen-norm position for a layer pixel whose own screen-norm position is
// (snX, snY) — offs is the AM point, layerAspect = the layer's width / height — and the
// edges fold (mode 0 repeat keeps the position raw and reports *tint = 1; the other modes
// fold into [0,1]^2 and report *tint = the pixel's alpha, AM's rule).
float fxDisplaceMapAmPick(float r, float g, float b, int type);
float fxDisplaceMapAmShape(float p, bool fromCtr, bool invert);
void fxDisplaceMapAmUv(float snX, float snY, float offX, float offY, float px, float py, float a,
                       float layerAspect, float* outX, float* outY);
void fxDisplaceMapAmEdge(float uvX, float uvY, int edges, float texA, float* outX, float* outY,
                         float* tint, bool* inside);
// CPU reference of Displace Map Radial (AM's displacemaprad): the displaced screen-norm
// position; (lcX, lcY) is the layer centre in screen norm, (cX, cY) the centre point in AM
// units, screenW / screenH the canvas size in pixels (AM's acScreenSize).
void fxDisplaceMapRadAmUv(float snX, float snY, float lcX, float lcY, float cX, float cY,
                          float offsAngleDeg, float offsRadius, float px, float py, float a,
                          float screenW, float screenH, float* outX, float* outY);
// CPU references of Smooth Bevel (AM's smoothbevel): the two-byte float packing of the
// X-distance buffer (encode into two 0..1 channels, decode back), and the final shading —
// the four smoothed corner distances (0..1) build the normal v; lumS / lumD are the two
// lights' factors (before colour / intensity / original.a are applied).
void fxSmoothBevelAmEncode(float v, float* out0, float* out1);
float fxSmoothBevelAmDecode(float e0, float e1);
void fxSmoothBevelAmLights(float tl, float tr, float bl, float br, float angleSdeg,
                           float angleDdeg, float elevS, float elevD, float spec,
                           float* outLumS, float* outLumD);
// CPU references of Light Glow (AM's lightglow): the pass-0 bright pick (premultiplied in
// and out; the colour is straight, its alpha 1 in AM) and the kernel size of the blur
// passes for a strength and a preview (canvas) size in pixels.
void fxLightGlowAmPick(float r, float g, float b, float a, float threshold, float blend,
                       float colR, float colG, float colB,
                       float* outR, float* outG, float* outB, float* outA);
float fxLightGlowAmKernel(float strength, float previewW, float previewH);
// Light Glow's composite (pass 2): base is the layer pixel, result the blurred bright pick
// (both premultiplied); intensity / halo / blend / alpha are AM raw, the colour straight.
void fxLightGlowAmComposite(float br, float bg, float bb, float ba,
                            float rr, float rg, float rb, float ra,
                            float intensity, float halo, float blend,
                            float colR, float colG, float colB, float alpha,
                            float* outR, float* outG, float* outB, float* outA);

// fx_plan.cpp twins of AM's inner-glow (glow-inner.xml): the pass stride
// q = max(radius2^1.24 / radius2, radius2 / 40) (the X/Y scans step by q pixels, so the
// tap count radius2/q never exceeds 40), the edge falloff p (AM's reversed smoothstep of
// the distance to the nearest transparent pixel), and the final blend + mix.
float fxInnerGlowAmStride(float radius2);
float fxInnerGlowAmP(float radius2, float hardness, float nearestInside);
void fxInnerGlowAmMap(float tr, float tg, float tb, float ta,
                      float cr, float cg, float cb, int blendMode, float alpha, float p,
                      float* outR, float* outG, float* outB, float* outA);

// ---- Extrude / Extrude Flat (Alight s3d-extrude; dedicated raymarch pass) ----
// The orientation matrix of the def's vertex shader: the def forms
// rotateAndOrient = orient * rz * rx * ry (orient = the layer's host orientation,
// the identity in every serialised instance) and multiplies ROW vectors by it; the
// column-vector equivalent — what m holds, row-major — is its transpose,
// (Rz*Rx*Ry)^T. Both of the def's uses (the march's dist() and the surface
// texture coordinate) apply that same multiply, so local = m * (world - centre).
void fxExtrudeRotateMat(float rxDeg, float ryDeg, float rzDeg, float m[9]);

// A sampler for the CPU twin: fills out[4] (premultiplied rgba) with texture `tex`
// (0 = the layer, 1 = its blurred copy) at layer-norm (u, v) — Alight's own space,
// origin top-left, y down.
struct FxExtrudeSampler {
    void (*sample)(const void* ctx, int tex, float u, float v, float out[4]) = nullptr;
    const void* ctx = nullptr;
};

// CPU twin of the extrude raymarch (the GLSL in renderer_effects.cpp mirrors it line by
// line from AM's def): the premultiplied output at layer-norm (ax, ay) of a picW x picH
// layer picture. `flat` renders Extrude Flat (same march, flat shading, no light model,
// the blur texture read as the layer itself).
void fxExtrudeAmPixel(const FxOp& op, bool flat, float ax, float ay, float picW, float picH,
                      const FxExtrudeSampler& s, float out[4]);

// ---- v175: Alight's effects tier 4 (defs-batch7; the GLSL twins live in fx_shaders.h
// colour modes 25/26, warp mode 27, style modes 19/20 and in renderer_effects.cpp's
// dedicated passes) ----
// CPU reference of Threshold (AM's threshold): the pixel is PREMULTIPLIED in and out;
// the luma is AM's (of the premultiplied rgb). feather / threshold are AM raw; the
// def's feather floor 0.00196 and the (1.003 + 2f) recentring are part of the maths.
void fxThresholdAmMap(float pr, float pg, float pb, float pa, float threshold, float feather,
                      bool invert, int blendMode, float* outR, float* outG, float* outB, float* outA);
// Spectral Map's channel curve (AM's mapChannel): the wrapped triangle over the
// luma with the squeeze-mixed floor f.
float fxSpectralmapAmMapChannel(float p, float f);
// CPU reference of Spectral Map (AM's spectralmap): PREMULTIPLIED in and out. The three
// colours are straight r / g / b triples (cols[9]); the output alpha is the pixel's own.
void fxSpectralmapAmMap(float pr, float pg, float pb, float pa, float phase, float repeat,
                        float alpha, float squeeze, float saturation, bool reverse,
                        bool scaleBrightness, const float cols[9],
                        float* outR, float* outG, float* outB, float* outA);
// CPU reference of Channel Map (AM's channelmap): PREMULTIPLIED in and out; the
// luminance is read off the premultiplied rgb (the def computes it before dividing),
// the pixel is un-premultiplied, its channels re-picked (src 0..6: R, G, B, A, luma
// 709, 0, 1), and re-premultiplied by the NEW alpha.
void fxChannelmapAmMap(float pr, float pg, float pb, float pa, int srcR, int srcG, int srcB,
                       int srcA, float* outR, float* outG, float* outB, float* outA);
// CPU reference of Halftone Lines (AM's halftonelines): the sample position for a
// fragment at (uvX, uvY) and its in-band distance *absDist (0..0.5). amount / angleDeg /
// phase are AM units (phase raw), aspect = picture width / height.
void fxHalftoneLinesUv(float uvX, float uvY, float amount, float angleDeg, float phase,
                       float aspect, float* outX, float* outY, float* absDist);
// Halftone Lines' colour decision: samp is the displaced sample (premultiplied), c1 / c2
// the two straight colours, texA the fragment's own alpha (AM scales the result by it).
void fxHalftoneLinesMap(float sampR, float sampG, float sampB, float sampA, float absDist,
                        float strength, const float c1[3], const float c2[3], float texA,
                        float* outR, float* outG, float* outB, float* outA);
// CPU reference of Turbulence / Clouds (AM's turbulence2): the field value p at a
// fragment after the intensity scale (the caller clamps p + bias to 0..1 before the
// colour mix, exactly where the def does it; the mix is fxTurbulence2AmMap).
// offs / parallax / stretch are AM points; aspect = picture width / height.
float fxTurbulence2AmField(float uvX, float uvY, float intensity, float scale, float evolution,
                           float seed, int octaves, float offX, float offY, float parX,
                           float parY, float stretchX, float stretchY, float aspect);
// Turbulence's compose: T is the fragment's pixel (premultiplied); rcolor the field
// colour (straight); blendMode AM's (0..2), alpha the final mix.
void fxTurbulence2AmMap(float tr, float tg, float tb, float ta, float colR, float colG,
                        float colB, int blendMode, float alpha,
                        float* outR, float* outG, float* outB, float* outA);
// CPU reference of Checker (AM's checker2): the cell parity p (0 or 1) at a fragment, and
// its compose (T premultiplied; the colour straight, its alpha 1 — AM declares it
// alpha="false"; the def's c*alpha + tex*(1-alpha) with alpha = p * tex.a is verbatim,
// squared p and all).
float fxChecker2AmP(float uvX, float uvY, float amount, float angleDeg, float posX, float posY,
                    float stretchX, float stretchY, float aspect);
void fxChecker2AmMap(float tr, float tg, float tb, float ta, float colR, float colG,
                     float colB, float p, bool punchout,
                     float* outR, float* outG, float* outB, float* outA);
// CPU references of Mosaic (AM's mosaic): one axis' snapped coordinate (the def's
// centre-relative mod snap in layer norm) and the sample count of a pass
// (pass 0 averages tilePx / 2 taps, pass 1 tilePx). The full two-pass pixel twin runs
// off a sampler (tex 0 = the layer, AM layer space like FxExtrudeSampler's): pass 1's
// base tap reads the INPUT at the y-snapped position, exactly like the def (not the
// x-averaged buffer — an AM quirk the pin holds).
float fxMosaicAmSnap(float coord, float phase, float size, float layerDim);
int fxMosaicAmSamples(float tilePx, int pass);
void fxMosaicAmPixel(const FxOp& op, float ax, float ay, float picW, float picH,
                     const FxExtrudeSampler& s, float out[4]);
// CPU references of Glow (AM's glow): the composite of the final pass given the two
// nearest distances (in px of the layer picture). radius2 is the def's scaled radius
// (fxInnerGlowAmStride gives its scan stride q — the same formula). The def's
// p <= 0.0001 discard is the identity here: the composite is continuous there
// (outColor -> 0), so the twin returns the original pixel. comp is the backdrop pixel
// (premultiplied; only its rgb is read, for blend modes 1/2).
void fxGlowAmMap(float oR, float oG, float oB, float oA, float compR, float compG, float compB,
                 float nearestIn, float nearestOut, float radius2, float hardness, float alpha,
                 float colR, float colG, float colB, float colA, int blendMode,
                 float* outR, float* outG, float* outB, float* outA);
// The box-average sample count of the defs' box passes: symmetric passes (boxblur3,
// feather) clamp the speed to 2.01..150.01 and take 2n-1 taps; boxblur2 (inner blur)
// clamps to 1.01..100.01 and takes n taps (the centre tap plus n-1 offset taps).
int fxBoxSamples(float speed, bool symmetric);
// CPU twin of Inner Blur (AM's innerblur): the chained boxblur2 passes + composite over
// a sampler (tex 0 = the layer). One composite per full chain, the chain repeated
// op.p[1] times with the output feeding the next round (the def's <iterations>).
void fxInnerBlurAmPixel(const FxOp& op, float ax, float ay, float picW, float picH,
                        const FxExtrudeSampler& s, float out[4]);
// ---- v177: Queue 15(f) tier-5 twins (defs-batch8; the GLSL twins are
// Renderer::fxBbMakerAm / fxUnsharpMaskAm / fxNewSharpenAm / fxAmBlendGenAm /
// fxDeepGlowAm / fxSpillCleanupAm in renderer_effects.cpp). (ax, ay) is the
// layer-norm position; all pictures premultiplied.
// bbmaker (tex 0 = the layer): the two wipe bands of the def, line by line —
// the colour band under the picture band, the sum x tex.a. Band projections
// run on layer norm; the band smoothsteps use fxSstep (the def's feather is
// hardwired 0, and its own smoothstepRev codifies step for equal edges).
void fxBbMakerPixel(const FxOp& op, float ax, float ay, float picW, float picH,
                    const FxExtrudeSampler& s, float out[4]);
// unsharpmask (tex 0 = the layer, tex 1 = its gaussianblur buffer at `strength`):
// the def's composite — adjColor mixed in by the luma-difference gate.
void fxUnsharpMaskPixel(const FxOp& op, float ax, float ay, float picW, float picH,
                        const FxExtrudeSampler& s, float out[4]);
// newsharpen (tex 0 = the layer): the half-res streak averages (the boxblur2
// kernel form; a ds sample reads the layer at the same point, the boxblur3
// twin precedent) + the screen composite, the pass list repeated `iter` times.
void fxNewSharpenAmPixel(const FxOp& op, float ax, float ay, float picW, float picH,
                         const FxExtrudeSampler& s, float out[4]);
// The blend-generator family (tex 0 = the layer): kind 0 gradshadow, 1
// gradientflow, 2 partx, 3 edgeline, 4 lightsweep, 5 3colorgrad — each def's
// fragColor, then its own blend-switch variant (FULL 25 for 0 / 5, SHORT 4 for
// 1 / 2 / 4, MINI 3 for 3) and its own output tail, line by line. Slot layouts
// on the FX_ constants. The centred spaces anchor to the layer picture (the
// house vUv convention): centred = (ax - 0.5, 0.5 - ay), y pre-scaled by
// picH / picW.
void fxAmBlendGenPixel(int kind, const FxOp& op, float ax, float ay, float picW, float picH,
                       const FxExtrudeSampler& s, float out[4]);
// deepglow v1 (tex 0 = the layer, tex 1 = its gaussianblur buffer sampled at
// the position offset, tex 2 = its boxblur2 buffer): the def's composite.
void fxDeepGlow1Pixel(const FxOp& op, float ax, float ay, float picW, float picH,
                      const FxExtrudeSampler& s, float out[4]);
// spill-cleanup (tex 0 = the layer, tex 1 = its gaussianblur buffer at
// `strength`): the ordered over-compositing streak folds (evaluated on
// demand, the smoothedges twin pattern) + the luma-replaced spill composite.
void fxSpillCleanupAmPixel(const FxOp& op, float ax, float ay, float picW, float picH,
                           const FxExtrudeSampler& s, float out[4]);
// CPU twin of Box Blur 3 (AM's boxblur3): the half-res symmetric averages over a
// sampler (tex 0 = the layer; a ds sample reads the layer at the same point — the copy
// passes are plain renders in AM, and the host test's bilinear sampler makes the two
// agree on the pinned fields).
void fxBoxBlur3AmPixel(const FxOp& op, float ax, float ay, float picW, float picH,
                       const FxExtrudeSampler& s, float out[4]);
// CPU twin of Feather (AM's feather): the chained quarter-res averages + the
// alpha-squared composite over a sampler (tex 0 = the layer, read like boxblur3's ds).
void fxFeatherAmPixel(const FxOp& op, float ax, float ay, float picW, float picH,
                      const FxExtrudeSampler& s, float out[4]);
// McGuire value noise (AM's electricedges helper, line by line): the vec2 noise at
// (x, y) written to out[2].
void fxElectricNoise(float x, float y, float out[2]);
// CPU twin of Electric Edges (AM's electricedges): the main pass over a sampler
// (tex 0 = the layer, tex 1 = its gaussian-blurred copy — the def's blurBuf, fed by
// op.r[6] through the renderer's blur), AM layer space.
void fxElectricEdgesAmPixel(const FxOp& op, float ax, float ay, float picW, float picH,
                            const FxExtrudeSampler& s, float out[4]);
// CPU twin of Smooth Edges (AM's smoothedges): the streak passes + final cut over a
// sampler (tex 0 = the layer, tex 1 = its gaussian-blurred copy at op.p[0] through the
// renderer's blur). hstreakAt / vstreakAt are evaluated on demand, so a pinned pixel
// costs a few hundred samples — the host test uses tiny pictures.
void fxSmoothEdgesAmPixel(const FxOp& op, float ax, float ay, float picW, float picH,
                          const FxExtrudeSampler& s, float out[4]);

// ---- v180 (build 143): Queue 15(j) CPU twins (the GLSL twins live in fx_shaders.h
// modes / renderer_effects.cpp dedicated programs; slot layouts on the FX_ constants).
// Sampler convention like the extrude tier: tex 0 = the layer picture, sampled at
// layer-norm (u, v) with v = 1 at the top; the engine's textures are CLAMP_TO_EDGE
// (like AM's own samplers), so the host test's samplers clamp the same way.
// negative: the inverted-luma chain at one pixel (the 3x3 taps are read through the
// sampler at the def's fixed 0.0015 offsets); `grainSeed` is the def's
// fract(time * 100) term (the GLSL derives it from uFrame, the twin takes it raw).
void fxNegativeAmPixel(const FxOp& op, float ax, float ay, const FxExtrudeSampler& s,
                       float grainSeed, float out[4]);
// tritone: the luma ramp at one pixel (tex = the sampled premultiplied pixel).
void fxTritoneAmPixel(const FxOp& op, const float tex[4], float out[4]);
// unmult: the matte chain at one pixel (cross taps at BOTH the feather and the
// cleanup offsets are read through the sampler, exactly like the GLSL).
void fxUnmultAmPixel(const FxOp& op, float ax, float ay, float picW, float picH,
                     const FxExtrudeSampler& s, float out[4]);
// denoise: the Sir Bird spiral average at one pixel (512x512 hard-coded res).
void fxDenoiseAmPixel(const FxOp& op, float ax, float ay, const FxExtrudeSampler& s,
                      float out[4]);
// minimaxblur: ONE axis pass at one pixel (pass 0 = horizontal over tex 0;
// pass 1 = vertical over tex 0 with tex 1 = the original, for keepEdge).
void fxMinimaxAmPixel(const FxOp& op, int pass, float ax, float ay, float picW, float picH,
                      const FxExtrudeSampler& s, float out[4]);
// rgbglitch: the slice + channel-offset composite at one pixel.
void fxRgbGlitchAmPixel(const FxOp& op, float ax, float ay, const FxExtrudeSampler& s,
                        float out[4]);
// fisheye_antifisheye: the remapped sample at one pixel.
void fxFisheyeAmPixel(const FxOp& op, float ax, float ay, float aspect,
                      const FxExtrudeSampler& s, float out[4]);
// chromaticvortexblur: the three rotational blurs + the mode composite at one pixel.
void fxVortexBlurAmPixel(const FxOp& op, float ax, float ay, float picW, float picH,
                         const FxExtrudeSampler& s, float out[4]);
// refractionwarp: the cos-field sample at one pixel; `timeSec` is AM's acTime
// (the GLSL derives it from uFrame at the grain clock, the twin takes it raw).
void fxRefractionAmPixel(const FxOp& op, float ax, float ay, float picW, float picH,
                         float timeSec, const FxExtrudeSampler& s, float out[4]);
// smartMotionBlurShake: the stepwise shake average at one pixel.
void fxTwitchAmPixel(const FxOp& op, float ax, float ay, float timeSec,
                     const FxExtrudeSampler& s, float out[4]);
// turbulentdisplace2: the displaced sample COORDINATE (the sampler read itself is
// the identity warp afterwards). aspect = picW / picH.
void fxTurbulent2AmUv(const FxOp& op, float ax, float ay, float aspect, float outUv[2]);
// shapefield: the layered shape-cell composite at one pixel.
void fxShapefieldAmPixel(const FxOp& op, float ax, float ay, float picW, float picH,
                         const FxExtrudeSampler& s, float out[4]);
// halftone_dots: the dot-screen composite at one pixel.
void fxHalftoneDotsAmPixel(const FxOp& op, float ax, float ay, float picW, float picH,
                           const FxExtrudeSampler& s, float out[4]);
// cc_glass: the bump-normal lighting at one pixel (bumpMap = the layer itself).
void fxCcGlassAmPixel(const FxOp& op, float ax, float ay, const FxExtrudeSampler& s,
                      float out[4]);
// cinematic_bokeh: the golden-spiral highlight-weighted average at one pixel.
void fxBokehAmPixel(const FxOp& op, float ax, float ay, float aspect,
                    const FxExtrudeSampler& s, float out[4]);
// lensblurobs: the pow-4 weighted spiral bokeh + blend switch at one pixel.
void fxLensBlurObsAmPixel(const FxOp& op, float ax, float ay, const FxExtrudeSampler& s,
                          float out[4]);
// solid_color_wipe: the wipe composite at one pixel (tex = the sampled pixel).
void fxSolidWipeAmPixel(const FxOp& op, float ax, float ay, const float tex[4], float out[4]);
// softblooom: the extract stage (half res) and the composite stage as pure pixels.
void fxSoftbloomAmExtract(const float tex[4], float threshold, float out[4]);
void fxSoftbloomAmComposite(const FxOp& op, const float base[4], const float bloom[4], float out[4]);
// lensd: the chromabbr pass pixel (tex 0 = the layer) and the main pass pixel
// (tex 0 = the layer, tex 1 = the chromabbr output in xdist); picW is the screen
// width the main pass's strength / screen-width term divides by.
void fxChromabbrAmPixel(const FxOp& op, float ax, float ay, float aspect,
                        const FxExtrudeSampler& s, float out[4]);
void fxLensdAmPixel(const FxOp& op, float ax, float ay, float picW,
                    const FxExtrudeSampler& s, float out[4]);
// softblooom: the def's blur kernel size (numBlurPixelsPerSide) for a strength on a
// screenW x screenH canvas — the same law fxLightGlowAmKernel pins for Light Glow.
float fxSoftbloomAmKernel(float strength, float screenW, float screenH);
// bccfilmglow (v181): the def's blur tap count for a quality level (the getTaps
// ladder: < 1.5 -> 8, < 2.5 -> 14, else 20).
float fxBccFilmGlowAmTaps(float quality);
// bccfilmglow: the extract stage (pass 0: the soft-knee threshold mask over the
// linear-light brightness) and the composite stage (pass 4) as pure pixels.
// inner = the inner blur result (glowC), outer = the outer blur result (glowD);
// px / py are the floored pixel coordinates the grain / dither hashes read and
// timeSec is AM's acTime (the GLSL derives it from the grain clock).
void fxBccFilmGlowAmExtract(const FxOp& op, const float src[4], float out[4]);
void fxBccFilmGlowAmComposite(const FxOp& op, const float base[4], const float inner[4],
                              const float outer[4], float px, float py, float timeSec,
                              float out[4]);

// Posterize: one channel 0..1 snapped to `levels` steps.
float fxPosterize(float v, float levels);
// Units of the animated effects: phase of the Wave Warp / Turbulent Displace at a pattern frame (24 / s), in turns.
float fxAutoPhase(int frame, float speed);

// ---- other maths ----
float fxSplitPixels(float amount, float unit);  // RGB split: 100 -> 2.5 % of the height
float fxGrainCell(float size, float unit);      // grain size in picture pixels (1..4 * unit)
int fxGrainFrame(int64_t timeMs);               // grain pattern changes 24 times a second
}  // namespace am
