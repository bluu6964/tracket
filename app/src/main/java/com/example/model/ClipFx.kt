package com.example.model

import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.math.roundToInt

/** One adjustable value of an effect (a slider in the Effects panel). */
data class FxParamDef(
    val name: String,
    val min: Float,
    val max: Float,
    val default: Float,
    val unit: String = "",
    /** One tick of the ruler slider = one [step] (values are whole steps from [min]; [default] must be one of them). */
    val step: Float = 1f
)

/**
 * An effect the C++ engine can run ([nativeType] = am::FxType in cpp/engine/fx_plan.h). The order and meaning of
 * [params] are the p0, p1, p2 of that effect there.
 */
data class FxDef(
    val id: String,
    val nativeType: Int,
    val name: String,
    val category: String,
    val blurb: String,
    val params: List<FxParamDef>
)

/** An effect applied to a layer. [values] follow the order of the effect's [FxDef.params]. */
@Serializable
data class ClipFx(
    val id: String = UUID.randomUUID().toString(),
    val type: String,
    val enabled: Boolean = true,
    val values: List<Float>
)

object ClipFxLibrary {
    const val BLUR = "gaussian_blur"
    const val GLOW = "glow"
    const val RGB_SPLIT = "rgb_split"
    const val GRAIN = "film_grain"
    const val VIGNETTE = "vignette"
    const val CHROMA_KEY = "chroma_key"
    // v140
    const val DIR_BLUR = "directional_blur"
    const val RADIAL_BLUR = "radial_blur"
    const val SHARPEN = "sharpen"
    const val EXPOSURE = "exposure"
    const val BRIGHT_CONTRAST = "brightness_contrast"
    const val HUE_SAT = "hue_saturation"
    const val LEVELS = "levels"
    const val COLOR_BALANCE = "color_balance"
    const val TINT = "tint"
    const val POSTERIZE = "posterize"
    const val WAVE = "wave_warp"
    const val TWIRL = "twirl"
    const val BULGE = "bulge"
    const val MIRROR = "mirror"
    const val KALEIDOSCOPE = "kaleidoscope"
    const val TURBULENT = "turbulent_displace"
    const val MOSAIC = "mosaic"
    const val HALFTONE = "halftone"
    const val SCANLINES = "scanlines"
    const val GLITCH = "glitch"
    // v149
    const val INVERT = "invert"
    const val BW = "black_white"
    const val THRESHOLD = "threshold"
    const val TEMPERATURE = "temperature"
    const val SHADOW_HIGHLIGHT = "shadow_highlight"
    const val LEAVE_COLOR = "color_pop"
    const val CINEMATIC = "cinematic"
    const val SOLARIZE = "solarize"
    const val LENS_BLUR = "lens_blur"
    const val TILT_SHIFT = "tilt_shift"
    const val SURFACE_BLUR = "surface_blur"
    const val LIGHT_RAYS = "light_rays"
    const val RIPPLE = "ripple"
    const val LENS_DISTORT = "lens_distortion"
    const val POLAR = "polar"
    const val TILE = "motion_tile"
    const val SHAKE = "camera_shake"
    const val EMBOSS = "emboss"
    const val FIND_EDGES = "find_edges"
    const val CARTOON = "cartoon"
    const val SKETCH = "pencil_sketch"
    const val OIL = "oil_paint"
    const val STROBE = "strobe_light"
    const val VHS = "vhs"
    const val OLD_FILM = "old_film"
    const val LIGHT_LEAK = "light_leak"
    const val LENS_FLARE = "lens_flare"
    // v157
    const val COPY_BG = "copy_background"
    // Motion Blur: resolved per frame by MotionBlurEval into a directional blur (see engine/MotionBlurEval.kt);
    // its native type (55) never reaches the shaders (fxParseOps skips it: it is above FX_MAX).
    const val MOTION_BLUR = "motion_blur"
    // Repeat: resolved in the draw loops by RepeatEval (see engine/RepeatEval.kt) — the layer is
    // drawn once per copy with a shifted / turned / scaled pose: overlay layers as copy clips,
    // video / photo and group layers as per-copy placements of their one picture (RepeatDraw).
    // Its native type (56) never reaches the shaders either (fxParseOps skips it: it is above
    // FX_MAX), and toNative skips it like Motion Blur.
    const val REPEAT = "repeat"
    // Repeat Radial / Repeat Scatter: the other two arrangements of Alight's repeat family (copies on a
    // ring / scattered over a disc), resolved by RepeatEval in the same draw loops; native types 57 / 58
    // sit above FX_MAX for the same reason.
    const val REPEAT_RADIAL = "repeat_radial"
    const val REPEAT_SCATTER = "repeat_scatter"
    // v160: two more SHADED effects (they DO reach the engine, unlike 55..58): Long Shadow (the layer's
    // silhouette extruded along an angle, kFxBlur2 mode 7) and Color Tune (Alight's Lift / Gain grading,
    // kFxColor mode 15). Their native types 59 / 60 sit above FX_MAX too, but fxParseOps accepts them
    // explicitly — see fx_plan.h.
    const val LONG_SHADOW = "long_shadow"
    const val COLOR_TUNE = "color_tune"
    // v161: Solid Color (Alight Motion's `solidcolor`), also shaded (kFxColor mode 16, native type 61):
    // the picture's colours are replaced by one colour, its alpha silhouette kept. Values [0..2] are
    // the colour 0..255, [3] the strength %. Value [4] is written by the Alight importer only: 1 when
    // the colour is keyframed there — SolidColorEval then resolves [0..2] per frame from the clip's
    // FILL_COLOR track (which the importer stores for exactly that colour animation).
    const val SOLID_COLOR = "solid_color"
    // v162: Alight Motion's own `mirror` and `glowscan`, from AM's recovered effect definitions
    // (after-motion-dev/hunt-staging/def_mirror.xml / def_glowscan.xml). AM_MIRROR is NOT the app's
    // MIRROR above (a centre/angle reflect-half): AM flips the WHOLE layer's sampling (type 0 =
    // Horizontal, 1 = Vertical), blends the flipped picture over the original per blendMode
    // (0 Normal, 1 Multiply, 2 Screen, 3 Over, 4 Under) and mixes by alpha. It is shaded
    // (kFxWarp mode 11, native type 62). Values [0] type, [1] blendMode, [2] alpha %, [3] offset %.
    // A keyframed alpha (15 of the 51 corpus instances: 0<->1 flip-flash pulses) rides the values
    // tail from index 7 as a keyframe track — AmFxEval resolves [2] per frame.
    const val AM_MIRROR = "am_mirror"
    // GLOW_SCAN is a dedicated two-texture pass in the engine (native type 63, like Glow at 2):
    // a gaussian-blurred copy of the layer (strength -> the blur, the applyGaussianBlur
    // calibration) is scanned by luma; inside the [threshold, threshold+radius] band the blurred
    // picture gains a pow() glow tinted toward fillColor. Values [0] threshold %, [1] radius %,
    // [2] power, [3] smoothing (AM strength) %, [4] fill %, [5] alpha %, [6] the fill colour
    // packed 0xRRGGBB (importer only, the Long Shadow pattern). AM's `blend` has no slot: the
    // importer reports it when it is not 0 (it is 0 in all 8 corpus instances). A keyframed
    // threshold (ALL 8 corpus instances — the sweep IS the effect) rides the tail like Mirror's.
    const val GLOW_SCAN = "glow_scan"
    // v163: Alight Motion's next tier, from the same recovered definitions (hunt-staging/
    // defs-batch2). LUMA_KEY is a dedicated pass (it scales alpha; native type 64). Values
    // [0] low threshold %, [1] high threshold %, [2] feather %, [3] invert, [4] weighted luma.
    // A keyframed low threshold (ALL 19 corpus instances — the sweep is the effect) rides the
    // values tail from index 7, resolved per frame by AmFxEval.
    const val LUMA_KEY = "luma_key"
    // FOUR_COLOR_GRADIENT (native 65 + EXT 66): values [0..3] the four colours packed 0xRRGGBB,
    // [4] blend %, [5] alpha %, [6] blend mode; values [7..10] (the EXT group) are the four
    // anchor points, each packed into one float by [packPoint]; [11..13] unused.
    const val FOUR_COLOR_GRADIENT = "four_color_gradient"
    // TURBULENT_DISPLACE_AM (native 67 + EXT 68): NOT the app's own TURBULENT (value-noise fbm);
    // this is Alight's simplex-noise displacement. Values [0] intensity %, [1] scale %,
    // [2] direction (0 X, 1 Y, 2 XY, 3 R), [3] evolution %, [4] octaves, [5] screen space,
    // [6] offset %; EXT [7] stretch %, [8] seed %, [9..10] position, [11..12] parallax (AM
    // units). Intensity AND scale are keyframed in all 39 corpus instances: two AmFxEval tails
    // ride from index 14.
    const val TURBULENT_DISPLACE_AM = "turbulent_displace_am"
    // SWIRL_AM (native 69, kFxWarp mode 13): Alight's swirl4 / swirl3 as one fx — values
    // [0] strength %, [1] radius %, [2..3] the centre point (AM units), [4] the version
    // (4 or 3). A keyframed strength rides the tail from index 7.
    const val SWIRL_AM = "swirl_am"
    // STRETCH_SEGMENT (native 70, kFxWarp mode 12): values [0] angle deg, [1] stretch
    // (AM units), [2] offset (AM units), [3] smooth %.
    const val STRETCH_SEGMENT = "stretch_segment"
    // HALFTONE_DOTS (native 71, kFxWarp mode 14): NOT the app's own HALFTONE style effect.
    // Values [0] amount (AM units), [1] strength %, [2] angle deg, [3] phase %,
    // [4..5] the two colours packed 0xRRGGBB.
    const val HALFTONE_DOTS = "halftone_dots"
    // PIXELATE_AM (native 72 + EXT 73): NOT the app's MOSAIC. Values [0] size (AM units),
    // [1..2] stretch x/y %, [3] angle deg, [4] vignette %, [5] screen space, [6] threshold %;
    // EXT [7] saturation %, [8..9] offset (AM units). A keyframed size rides the tail from
    // index 14 (AmFxEval scale 1: the slot already holds AM units).
    const val PIXELATE_AM = "pixelate_am"
    // REPLACE_COLOR (native 74, kFxColor mode 17): values [0] the old colour packed 0xRRGGBB,
    // [1] the new colour packed, [2] threshold %, [3] feather %, [4] alpha %,
    // [5] lock luminance.
    const val REPLACE_COLOR = "replace_color"
    // v164: Alight Motion's parked tier, hunt round 2 (defs-batch3 — every definition recovered
    // from the same public APK asset dump). CHROMA_KEY_AM (native 75) is NOT the app's own
    // CHROMA_KEY: AM measures the YUV distance (luma x0.25) to the key colour on the
    // unpremultiplied pixel. Dedicated pass. Values [0] the key colour packed 0xRRGGBB,
    // [1] threshold %, [2] feather %, [3] defringe, [4] invert. Keyframed threshold / feather
    // ride the tail from index 7.
    const val CHROMA_KEY_AM = "chroma_key_am"
    // COLOR_BALANCE_AM (native 76 + EXT 77, kFxColor mode 18): NOT the app's own
    // COLOR_BALANCE (hue discs). Values [0..6] crLow, mgLow, ybLow, crMid, mgMid, ybMid,
    // crHigh (AM units -100..100); EXT [7] mgHigh, [8] ybHigh, [9] fix luminance. Every band
    // value may ride a tail from index 14.
    const val COLOR_BALANCE_AM = "color_balance_am"
    // COLOR_TEMP_AM (native 78, kFxColor mode 19): values [0] temperature (AM units:
    // Kelvin / 1000), [1] strength %. Both may ride tails from index 7.
    const val COLOR_TEMP_AM = "color_temp_am"
    // COLOR_TINT_AM (native 79, kFxColor mode 20): values [0] tint hue deg (AM raw),
    // [1] strength %. Both may ride tails from index 7.
    const val COLOR_TINT_AM = "color_tint_am"
    // SPIN_BLUR_AM (native 80, kFxBlur2 mode 8): NOT the app's RADIAL_BLUR (fixed 48 taps) —
    // AM's tap count follows the arc length at each radius. Values [0] angle deg (AM raw),
    // [1..2] the centre point (AM units). A keyframed angle rides the tail from index 7.
    const val SPIN_BLUR_AM = "spin_blur_am"
    // OFFSET_AM (native 81, kFxWarp mode 15): values [0..1] offset x/y (AM units),
    // [2] feather %, [3] mask. A keyframed offset rides two tails (x, then y) from index 7.
    const val OFFSET_AM = "offset_am"
    // RADIAL_WIPE_AM (native 82, kFxWarp mode 16): values [0] progress deg, [1] feather deg,
    // [2] direction, [3] repeat count, [4] the centre point packed, [5] offset deg.
    // A keyframed progress rides the tail from index 7.
    const val RADIAL_WIPE_AM = "radial_wipe_am"
    // FIND_EDGES_AM (native 83, kFxStyle mode 14): NOT the app's own FIND_EDGES style.
    // Values [0] smoothing (AM raw), [1] threshold %, [2] invert.
    const val FIND_EDGES_AM = "find_edges_am"
    // HEX_SHIFT_AM (native 84 + EXT 85, kFxWarp mode 17): values [0] strength (AM raw),
    // [1] size (AM units), [2..3] stretch x/y %, [4] angle deg (AM raw), [5] seed,
    // [6] screen space; EXT [7] mask to layer, [8] the position point packed. Keyframed
    // strength / angle ride tails from index 14.
    const val HEX_SHIFT_AM = "hex_shift_am"
    // SOFT_GLOW_AM (native 86 + EXT 87): dedicated two-texture pass like GLOW_SCAN. Values
    // [0] strength %, [1] brightness %, [2] contrast %, [3] highlights %, [4] the colour
    // packed 0xRRGGBB, [5] blend %, [6] alpha %; EXT [7] outside alpha % (the backdrop
    // branch is not reproduced: the importer warns when it is not 0). A keyframed strength
    // rides the tail from index 14.
    const val SOFT_GLOW_AM = "soft_glow_am"
    // FILL_BEHIND_AM (native 88 + EXT 89): dedicated two-texture pass. Values [0] strength %,
    // [1] feather %, [2] gamma (x100), [3] tint strength %, [4] the tint colour packed
    // 0xRRGGBB, [5] edge strength %, [6] scan lines %; EXT [7] scan line count, [8] bg
    // scale %, [9] the edge colour packed, [10..11] bg offset x/y (AM units), [12] alpha %,
    // [13] saturation %. A keyframed bg offset rides two tails from index 14 (values 10/11);
    // AM's brightness / fgAlpha / fgScale / fgOffset have no slot (importer warns when one
    // is not at its default).
    const val FILL_BEHIND_AM = "fill_behind_am"
    // WAVE_WARP_AM (native 90 + EXT 91, kFxWarp mode 18): Alight's wavewarp2. Values
    // [0] phase (turns), [1] angle 1 deg, [2] spacing m1, [3] magnitude m2, [4] angle 2
    // deg (relative), [5] damping, [6] damping spacing; EXT [7] damping origin,
    // [8] screen space (the pattern is layer-anchored here, like every warp; the flag is
    // carried but does not change the maths). Keyframed phase / magnitude / angles ride
    // tails from index 14.
    const val WAVE_WARP_AM = "wave_warp_am"
    // CIRCULAR_RIPPLE_AM (native 92, kFxWarp mode 19): Alight's circularripple2 (=
    // circularripple, byte-identical shader). Values [0] strength, [1] radius,
    // [2] frequency, [3] feather, [4] phase (turns), [5..6] centre x/y (AM units).
    // Keyframed frequency / strength / centre ride tails from index 7.
    const val CIRCULAR_RIPPLE_AM = "circular_ripple_am"
    // STRETCH_AM (native 93, kFxWarp mode 20): Alight's stretch2 / stretch — the two
    // versions share the map, [3] selects the contentOnly compose (1 = v1 composites the
    // layer under the masked sample, 2 = v2 keeps it). Values [0] scale, [1] angle deg,
    // [2] content only. Keyframed scale / angle ride tails from index 7.
    const val STRETCH_AM = "stretch_am"
    // SQUEEZE_AM (native 94, kFxWarp mode 21): Alight's squeeze. Its def also declares a
    // centre point and a radius, but the shader never reads them — [0] strength is all.
    // A keyframed strength rides the tail from index 7.
    const val SQUEEZE_AM = "squeeze_am"
    // FRACTAL_WARP_AM (native 95 + EXT 96, kFxWarp mode 22): Alight's fractalwarp4.
    // Values [0] magnitude, [1] scale, [2] intensity, [3] octaves, [4..5] the offset
    // point x/y, [6] parallax x; EXT [7] parallax y (components, so keyframed points
    // animate). Keyframed magnitude / offset / parallax / scale ride tails from
    // index 14.
    const val FRACTAL_WARP_AM = "fractal_warp_am"
    // STRIPES_AM (native 97, kFxWarp mode 23): Alight's stripes. Values [0] amount,
    // [1] width, [2] angle deg, [3] phase, [4] the colour packed 0xRRGGBB, [5] smoothing,
    // [6] punch out. (The def's screenSpace branch is not reproduced; the importer warns
    // when an instance sets it.) Keyframed phase / width ride tails from index 7.
    const val STRIPES_AM = "stripes_am"
    // TURBULENT_DISPLACE3_AM (native 98 + EXT 99, kFxWarp mode 24): Alight's
    // turbulentdisplace3. Values [0] intensity, [1] scale, [2] evolution, [3] seed,
    // [4] direction (0 X, 1 Y, 2 XY, 3 R, 4 φr, 5 φ), [5] octaves, [6] offset; EXT
    // [7..8] the parallax point x/y, [9..10] the position point x/y, [11..12] stretch
    // x/y, [13] flags (screen space + 2 * single axis; screen space only swaps the
    // stretch here — the pattern stays layer-anchored). Keyframed intensity / scale
    // ride tails from index 14.
    const val TURBULENT_DISPLACE3_AM = "turbulent_displace3_am"
    // ROUGHEN_EDGES_AM (native 100, kFxWarp mode 25): Alight's roughenedges — its shader
    // is turbulentdisplace3's body with the geometry fixed (direction R, detail x5,
    // offset 0.15); the def's other declared params are never read by the shader. Values
    // [0] strength, [1] detail, [2] evolution, [3] seed, [4] octaves. A keyframed
    // evolution rides the tail from index 7.
    const val ROUGHEN_EDGES_AM = "roughen_edges_am"
    // STREAKS_AM (native 101 + EXT 102, kFxBlur2 mode 9): Alight's streaks (linear).
    // Values [0] strength, [1] angle deg, [2] bias, [3] alpha, [4..6] the R/G/B combine
    // modes; EXT [7] the A combine mode (modes: 0 min, 1 max, 2 off, 3 average). A
    // keyframed strength rides the tail from index 14.
    const val STREAKS_AM = "streaks_am"
    // COLOR_HOT_AM (native 103, kFxColor mode 21): Alight's colorhot. Values are the two
    // hue-discs flattened: [0..2] colour hue deg / saturation / bias, [3..5] tint hue /
    // saturation / bias.
    const val COLOR_HOT_AM = "color_hot_am"
    // COLORIZE_AM (native 104, kFxColor mode 22): Alight's colorize — the Color Tint
    // formula with the hue-disc's saturation as the amount. Values [0] tint hue deg,
    // [1] amount.
    const val COLORIZE_AM = "colorize_am"
    // HIGHLIGHT_SHADOW_AM (native 105, kFxColor mode 23): Alight's highlightshadow
    // (GPUImage's formula). Values [0] shadows, [1] highlights (AM raw 0..2; both 1 =
    // identity).
    const val HIGHLIGHT_SHADOW_AM = "highlight_shadow_am"
    // SPOT_COLOR_AM (native 106, kFxColor mode 24): Alight's spotcolor2 (group 0).
    // Values [0] the colour packed 0xRRGGBB, [1] threshold, [2] feather, [3] saturation,
    // [4] choke.
    const val SPOT_COLOR_AM = "spot_color_am"
    // DISSOLVE_AM (native 107, kFxStyle mode 15): Alight's dissolve. Values [0] amount,
    // [1] seed. A keyframed amount rides the tail from index 7.
    const val DISSOLVE_AM = "dissolve_am"
    // SIMPLE_STARS_AM (native 108 + EXT 109, kFxStyle mode 16): Alight's simplestars2.
    // Values [0] scale, [1] star size, [2] star feather, [3] the colour packed 0xRRGGBB,
    // [4] star count, [5] brightness, [6] blend mode (0 normal, 1 multiply, 2 screen,
    // 3 punch out); EXT [7..9] the parallax x/y/z, [10] alpha. Keyframed parallax
    // (three tails) / star size ride tails from index 14.
    const val SIMPLE_STARS_AM = "simple_stars_am"
    // DOTS_AM (native 110 + EXT 111, kFxStyle mode 17): Alight's dots2. Values [0] scale,
    // [1] phase (turns), [2] radius, [3] feather, [4..5] the two colours packed 0xRRGGBB,
    // [6] density; EXT [7..8] the position point x/y, [9] fill, [10] alpha, [11] blend
    // mode (0 normal, 1 multiply, 2 screen), [12] seed, [13] slack. Keyframed phase /
    // density ride tails from index 14.
    const val DOTS_AM = "dots_am"
    // RIDGES_AM (native 112 + EXT 113, kFxStyle mode 18): Alight's ridges2 / ridges.
    // Values [0] intensity, [1] scale, [2] evolution, [3] seed, [4] octaves, [5..6] the
    // two colours packed 0xRRGGBB (their alpha is dropped, like every packed colour);
    // EXT [7..8] the offset point x/y, [9..10] the parallax point x/y, [11..12] stretch
    // x/y (v1: [11] is the scalar stretch), [13] version (1 or 2). Keyframed offset /
    // parallax / evolution ride tails from index 14.
    const val RIDGES_AM = "ridges_am"
    // BEND_WARP_AM (native 114, kFxWarp mode 26): Alight's bendwarp. Values [0] amount
    // deg, [1] offset, [2] origin (0 left, 1 right, 2 top, 3 bottom), [3] opposite type,
    // [4] high limit, [5] low limit. Keyframed amount / offset ride tails from index 7.
    const val BEND_WARP_AM = "bend_warp_am"
    // DISPLACE_MAP_AM (native 115): dedicated backdrop pass (renderer_effects.cpp).
    // Alight's displacemap3 (= displacemap2): the def's `comp` input is srcType "comp" —
    // the composite of everything BELOW the layer, which is exactly the backdrop the
    // renderer captures for Copy Background. Values [0..1] the offset point x/y,
    // [2] x source, [3] y source (0 luma, 1 R, 2 G, 3 B), [4] edges (0 repeat, 1 mirror,
    // 2 transparent, 3 tile), [5] from centre, [6] invert. A keyframed offset rides two
    // tails from index 7.
    const val DISPLACE_MAP_AM = "displace_map_am"
    // DISPLACE_MAP_RAD_AM (native 116 + EXT 117): dedicated backdrop pass. Alight's
    // displacemaprad. Values [0] offset angle deg, [1] offset radius, [2] x source,
    // [3] y source, [4] edges, [5] from centre, [6] invert; EXT [7..8] the centre point
    // x/y. A keyframed offset angle rides the tail from index 14.
    const val DISPLACE_MAP_RAD_AM = "displace_map_rad_am"
    // SMOOTH_BEVEL_AM (native 118 + EXT 119): dedicated two-pass effect
    // (renderer_effects.cpp). Alight's smoothbevel. Values [0] radius, [1] diffuse angle
    // deg, [2] specular angle deg, [3] diffuse intensity, [4] specular intensity,
    // [5] diffuse elevation, [6] specular elevation; EXT [7] specular coefficient,
    // [8] the diffuse colour packed 0xRRGGBB, [9] the specular colour packed. A keyframed
    // specular angle rides the tail from index 14.
    const val SMOOTH_BEVEL_AM = "smooth_bevel_am"
    // LIGHT_GLOW_AM (native 120): dedicated four-pass effect (renderer_effects.cpp).
    // Alight's lightglow — all four passes share one shader in the def (no host
    // downsample shader after all; its buffers are plain downsample="2" targets).
    // Values [0] strength, [1] threshold, [2] intensity, [3] the colour packed 0xRRGGBB,
    // [4] blend, [5] halo, [6] alpha.
    const val LIGHT_GLOW_AM = "light_glow_am"
    // INNER_GLOW_AM (native 121): dedicated two-pass effect (renderer_effects.cpp).
    // Alight's inner-glow — missed by the id-list censuses until the v165 full sweep
    // (one visible corpus instance). Values [0] radius, [1] alpha, [2] hardness,
    // [3] the colour packed 0xRRGGBB (its alpha is declared but never read by the
    // shader), [4] blend mode (0 normal, 1 multiply, 2 screen).
    const val INNER_GLOW_AM = "inner_glow_am"
    // DARK_GLOW_AM (native 122): dedicated pass chain (renderer_effects.cpp). Alight's
    // darkglow, ported from its def (build 120): the layer's dark parts (luma below the
    // threshold), tinted toward the colour by blend, are blurred and multiplied back over
    // the layer at alpha — a glow of the shadows, not of the highlights. Values
    // [0] strength % (AM raw x100), [1] threshold %, [2] the colour packed 0xRRGGBB,
    // [3] blend %, [4] alpha %. Keyframed strength / threshold ride tails from index 7.
    const val DARK_GLOW_AM = "dark_glow_am"
    // EXTRUDE_AM (native 123 + EXT 124 + EXT2 125): dedicated raymarch pass
    // (renderer_effects.cpp). Alight's s3d-extrude, ported from its def: the layer's
    // silhouette extruded into a 3D box, sphere-traced against a gaussian-blurred copy
    // of itself. Values [0] depth, [1] scale, [2] width ratio, [3] height ratio,
    // [4] interior blend, [5] fade blend, [6] shading type (0 flat, 1 point light,
    // 2 directional, 3 depth map, 4 normal map, 5 normal & depth) — all AM raw units.
    // EXT [7..9] rotate xyz deg, [10..12] position xyz, [13] the interior colour packed
    // 0xRRGGBB. EXT2 [14] the fade colour packed, [15] the light colour packed,
    // [16] the light direction point packed, [17] light intensity + strength packed
    // (x100 each: intensity + strength*256), [18] shine + ambient packed (x100 each:
    // shine*128 + ambient), [19] spec, [20] light depth + MAX_ITER packed
    // ((depth+100)*2 + iter*2048). Keyframed rotate / position components ride six
    // AmFxEval tails from index 21.
    const val EXTRUDE_AM = "extrude_am"
    // EXTRUDE_FLAT_AM (native 126 + EXT 127 + EXT2 128): Alight's extrudeflat — no def
    // exists in either recovered dump; the port shares Extrude's march with flat
    // shading and no light model (its serialised schema carries no light params).
    // Values [0] depth, [1] scale, [2] stretch x, [3] stretch y, [4] interior blend,
    // [5] fade blend, [6] inton (1 in all 16 corpus instances, with interior blend 1;
    // its exact role is unpinned — the interior colouring is applied as serialized).
    // EXT like Extrude's; EXT2 [14] the fade colour packed, [15] MAX_ITER.
    // Keyframed rotate / position ride tails from index 21, like Extrude's.
    const val EXTRUDE_FLAT_AM = "extrude_flat_am"
    // EDGE_GLOW_AM (native 129 + EXT 130): Alight's edgeglow, ported from its def
    // (recovered identically from two dumps). A dedicated three-pass chain
    // (renderer_effects.cpp): the def's findedges-edgeglow pass, the host gaussianblur
    // of the edge buffer, then the def's composite. Values are AM raw units:
    // [0] smoothing 1..10, [1] threshold 0..1, [2] invert 0/1, [3] strength (the blur)
    // 0..2, [4] spread 0..3, [5] fill color packed 0xRRGGBB, [6] blend 0..2.
    // EXT [7] gamma, [8] brightness, [9] tint color packed 0xRRGGBB, [10] tint.
    const val EDGE_GLOW_AM = "edge_glow_am"
    // ---- v175: Alight's effects tier 4 (defs-batch7; every def recovered from two
    // dumps, shader-identical between them). Values are AM raw units throughout.
    // THRESHOLD_AM (native 131): kFxColor mode 25. [0] threshold 0..1, [1] feather
    // 0..1, [2] invert 0/1, [3] blend 0 mask / 1 multiply / 2 screen. (The app's own
    // Threshold is a different effect.)
    const val THRESHOLD_AM = "threshold_am"
    // SPECTRALMAP_AM (native 132 + EXT 133): kFxColor mode 26. [0] phase -99..99,
    // [1] repeat 0.1..10, [2] alpha 0..1, [3] squeeze 0..1, [4] saturation -1..1,
    // [5] reverse 0/1, [6] scale brightness 0/1. EXT [7..9] the red / green / blue
    // colours packed 0xRRGGBB (their alpha never reaches the maths).
    const val SPECTRALMAP_AM = "spectralmap_am"
    // CHANNELMAP_AM (native 134): dedicated pass (it rewrites the alpha).
    // [0..3] the R / G / B / A sources (0 R, 1 G, 2 B, 3 A, 4 luma, 5 zero, 6 one).
    const val CHANNELMAP_AM = "channelmap_am"
    // HALFTONE_LINES_AM (native 135): kFxWarp mode 27. [0] amount 0..500,
    // [1] strength 0..1, [2] angle deg, [3] phase (AM raw -1000..1000),
    // [4] / [5] the two colours packed 0xRRGGBB. Keyframed angle / phase ride
    // AmFxEval tails from index 7.
    const val HALFTONE_LINES_AM = "halftone_lines_am"
    // TURBULENCE2_AM (native 136 + EXT 137 + EXT2 138): kFxStyle mode 19.
    // [0] intensity, [1] scale, [2] evolution, [3] seed, [4] / [5] colours 1/2
    // packed, [6] blend 0..2. EXT [7] / [8] offs x/y, [9] / [10] parallax x/y,
    // [11] / [12] stretch x/y, [13] bias. EXT2 [14] alpha, [15] octaves. Keyframed
    // offs / parallax components ride four AmFxEval tails from index 21.
    const val TURBULENCE2_AM = "turbulence2_am"
    // CHECKER2_AM (native 139 + EXT 140): kFxStyle mode 20. [0] amount 0.5..200,
    // [1] angle deg, [2] colour packed, [3] punch out 0/1, [4] screen space 0/1
    // (carried; the pattern anchors to the layer's rect), [5] / [6] position x/y.
    // EXT [7] / [8] stretch x/y.
    const val CHECKER2_AM = "checker2_am"
    // MOSAIC_AM (native 141): dedicated two-pass. [0] size 1..500 (layer px),
    // [1] / [2] phase x/y. Keyframed size rides an AmFxEval tail from index 7.
    // (The app's own Mosaic style is a different effect.)
    const val MOSAIC_AM = "mosaic_am"
    // GLOW_AM (native 142): dedicated outer-glow scan. [0] radius 0..400,
    // [1] alpha, [2] hardness, [3] colour packed, [4] colour alpha (it scales the
    // result's alpha in blend mode 0), [5] blend 0..2. (The app's own Glow bloom
    // is a different effect.)
    const val GLOW_AM = "glow_am"
    // INNER_BLUR_AM (native 143): dedicated box-chain pass. [0] strength 0..1,
    // [1] iterations 1..6. Keyframed strength rides an AmFxEval tail from index 7.
    const val INNER_BLUR_AM = "inner_blur_am"
    // BOX_BLUR3_AM (native 144): dedicated half-res box pass. [0] strength 0..2.
    const val BOX_BLUR3_AM = "box_blur3_am"
    // FEATHER_AM (native 145): dedicated quarter-res box chain. [0] strength 0..2,
    // [1] iterations 1..5. Keyframed strength rides an AmFxEval tail from index 7.
    const val FEATHER_AM = "feather_am"
    // ELECTRIC_EDGES_AM (native 146 + EXT 147 + EXT2 148): dedicated pass (blur
    // buffer + the def's main pass). [0] distance, [1] thickness, [2] complexity,
    // [3] disort, [4] / [5] colours 1/2 packed, [6] alpha. EXT [7] glow alpha,
    // [8] seed, [9] evolution, [10] fill, [11] mask strength, [12] mask evolution,
    // [13] mask scale. EXT2 [14] mask seed, [15] mask colour packed, [16] render
    // contours, [17] contour count, [18] contour offset, [19] contour angle,
    // [20] strength (the fixed 0.42 feeding the def's gaussianblur buffer).
    // Keyframed evolution rides an AmFxEval tail at index 21 + 2.
    const val ELECTRIC_EDGES_AM = "electric_edges_am"
    // SMOOTH_EDGES_AM (native 149): dedicated pass (blur copy + streak composites).
    // [0] strength 0..2, [1] mask to layer 0/1.
    const val SMOOTH_EDGES_AM = "smooth_edges_am"

    /**
     * Most effects on one layer (kFxMaxOps in cpp/engine/fx_plan.h — the pair is ONE bound:
     * the renderer's fixed op array, mirrored here for the importer, [toNative] and the Effects
     * panel). 32 since build 130 (Queue 14h): the largest stack in the 93-file Alight corpus is
     * 29 (blxze-brepizx-pack), and the old 12 silently ate real, implemented effects off such
     * layers. The cost of the bound is the array itself (32 x 88 B per layer draw); project
     * files store the effect list unbounded, so nothing else assumes this number.
     */
    const val MAX_EFFECTS = 32

    val categories = listOf("Blur", "Color", "Light", "Lens", "Distort", "Stylize", "Texture", "Keying", "Backdrop")

    val all: List<FxDef> = listOf(
        FxDef(
            BLUR, 1, "Gaussian Blur", "Blur", "True Gaussian, smooth at any size",
            listOf(FxParamDef("Blurriness", 0f, 100f, 20f))
        ),
        FxDef(
            GLOW, 2, "Glow", "Light", "Bright areas bloom softly",
            listOf(
                FxParamDef("Threshold", 0f, 100f, 60f, "%"),
                FxParamDef("Radius", 0f, 100f, 50f),
                FxParamDef("Intensity", 0f, 300f, 100f, "%", step = 4f)
            )
        ),
        FxDef(
            RGB_SPLIT, 3, "RGB Split", "Lens", "Red and blue drift apart",
            listOf(
                FxParamDef("Amount", 0f, 100f, 30f),
                FxParamDef("Angle", 0f, 360f, 0f, "°", step = 3f),
                FxParamDef("Radial", 0f, 100f, 0f, "%")
            )
        ),
        FxDef(
            VIGNETTE, 5, "Vignette", "Lens", "Darkens the edges",
            listOf(
                FxParamDef("Amount", 0f, 100f, 50f, "%"),
                FxParamDef("Size", 0f, 100f, 50f, "%"),
                FxParamDef("Softness", 0f, 100f, 60f, "%")
            )
        ),
        FxDef(
            GRAIN, 4, "Film Grain", "Texture", "Moving film grain, 24 fps",
            listOf(
                FxParamDef("Amount", 0f, 100f, 35f, "%"),
                FxParamDef("Size", 0f, 100f, 30f, "%"),
                FxParamDef("Colour", 0f, 100f, 20f, "%")
            )
        ),
        // Hue 120 = green screen, 240 = blue screen. Key colour strength = how vivid the screen is (a dull screen needs less).
        FxDef(
            CHROMA_KEY, 6, "Chroma Key", "Keying", "Removes a green / blue screen",
            listOf(
                FxParamDef("Key hue", 0f, 360f, 120f, "°", step = 3f),
                FxParamDef("Key strength", 0f, 100f, 60f, "%"),
                FxParamDef("Similarity", 0f, 100f, 35f, "%"),
                FxParamDef("Smoothness", 0f, 100f, 15f, "%"),
                FxParamDef("Spill removal", 0f, 100f, 50f, "%"),
                FxParamDef("Key brightness", 0f, 100f, 100f, "%"),
                FxParamDef("Brightness match", 0f, 100f, 0f, "%")
            )
        ),
        // ---- v140: blur ----
        FxDef(
            DIR_BLUR, 7, "Directional Blur", "Blur", "Motion blur along an angle, optional comet trail",
            listOf(
                FxParamDef("Angle", 0f, 360f, 0f, "°", step = 3f),
                FxParamDef("Length", 0f, 100f, 30f),
                FxParamDef("Trail", 0f, 100f, 0f, "%")
            )
        ),
        // Motion Blur is resolved per frame by MotionBlurEval: values[0] is the Strength the panel
        // shows; values[1..3] (usePos / useScale / useAngle, 1 = on) are written by the Alight
        // importer and default to on when absent — keep them when editing values (see
        // ClipEffectsPanel), or an imported scale-only blur silently becomes blur-all.
        FxDef(
            MOTION_BLUR, 55, "Motion Blur", "Blur", "Smears the layer along its own motion, like a camera shutter (position, scale and rotation)",
            listOf(FxParamDef("Strength", 0f, 100f, 100f, "%"))
        ),
        // Repeat is resolved in the draw loop, not a shader: values[0] is the copy count, [1..2] the
        // offset in preview px between copies, [3] the turn per copy, [4] the scale per copy (%),
        // [5] the opacity per copy (%). A Repeat made in the panel starts as two identical copies.
        FxDef(
            REPEAT, 56, "Repeat", "Distort", "Copies the layer in a row, each shifted, turned and scaled",
            listOf(
                FxParamDef("Count", 1f, 50f, 2f),
                FxParamDef("Offset X", -500f, 500f, 0f, "px", step = 5f),
                FxParamDef("Offset Y", -500f, 500f, 0f, "px", step = 5f),
                FxParamDef("Angle", -360f, 360f, 0f, "°", step = 3f),
                FxParamDef("Scale", 0f, 200f, 100f, "%", step = 5f),
                FxParamDef("Alpha", 0f, 100f, 100f, "%", step = 5f)
            )
        ),
        // Repeat Radial is resolved in the draw loop like Repeat: values[0] is the copy count,
        // [1] the ring radius in preview px, [2] the first copy's angle, [3] the sweep the copies
        // share, [4] the copies' orientation, [5] the copies' scale (%), [6] the copies' opacity
        // (%). Values [7] (the per-copy turn), [8] (the per-copy scale step) and [9..11] (the
        // alternating copies' colour, packed + flag) are written by the Alight importer only and
        // survive panel edits like every value past the declared params.
        FxDef(
            REPEAT_RADIAL, 57, "Repeat Radial", "Distort", "Copies the layer on a circle around itself",
            listOf(
                FxParamDef("Count", 1f, 50f, 4f),
                FxParamDef("Radius", 0f, 2000f, 200f, "px", step = 5f),
                FxParamDef("Start Angle", -360f, 360f, 0f, "°", step = 3f),
                FxParamDef("Sweep", 0f, 360f, 360f, "°", step = 3f),
                FxParamDef("Orientation", -360f, 360f, 0f, "°", step = 3f),
                FxParamDef("Copy Scale", 0f, 200f, 100f, "%", step = 5f),
                FxParamDef("Alpha", 0f, 100f, 100f, "%", step = 5f)
            )
        ),
        // Repeat Scatter likewise: values[0] is the copy count, [1] the field radius in preview px,
        // [2..3] the field offset, [4] the field seed, [5] the copy scale floor (%), [6] the copies'
        // opacity (%). Values [7] (the field angle), [8..11] (the evolution track: t0, v0, t1, v1)
        // and [12..14] (the alternating copies' colour, packed + flag) are the importer's.
        FxDef(
            REPEAT_SCATTER, 58, "Repeat Scatter", "Distort", "Scatters copies of the layer over a disc",
            listOf(
                FxParamDef("Count", 1f, 50f, 12f),
                FxParamDef("Radius", 0f, 2000f, 400f, "px", step = 5f),
                FxParamDef("Offset X", -1000f, 1000f, 0f, "px", step = 5f),
                FxParamDef("Offset Y", -1000f, 1000f, 0f, "px", step = 5f),
                FxParamDef("Seed", 0f, 1f, 0f, "", step = 0.01f),
                FxParamDef("Copy Scale", 0f, 100f, 20f, "%", step = 5f),
                FxParamDef("Alpha", 0f, 100f, 100f, "%", step = 5f)
            )
        ),
        // Long Shadow is a shader effect (kFxBlur2 mode 7): values[0] is the shadow length in % of
        // the picture height, [1] its angle, [2] its opacity, [3] its solidity (soft averaged ->
        // hard extruded), [4] its fade along the length. Value [5] (the shadow colour, packed
        // 0xRRGGBB) is written by the Alight importer only and survives panel edits like every
        // value past the declared params; absent / 0 = black, which is also the panel look.
        FxDef(
            LONG_SHADOW, 59, "Long Shadow", "Stylize", "The layer's silhouette stretched into a long flat shadow",
            listOf(
                FxParamDef("Length", 0f, 100f, 20f, "%"),
                FxParamDef("Angle", 0f, 360f, 45f, "°", step = 3f),
                FxParamDef("Opacity", 0f, 100f, 60f, "%"),
                FxParamDef("Solidity", 0f, 100f, 80f, "%"),
                FxParamDef("Fade", 0f, 100f, 0f, "%")
            )
        ),
        // Color Tune is a shader effect (kFxColor mode 15): a Lift and a Gain control, each a
        // colour-disc push (hue + strength) and a luminance shift, weighted by the pixel's own
        // luminance (Lift (1-y)^2, Gain y^2) — Alight Motion's colour grading effect.
        FxDef(
            COLOR_TUNE, 60, "Color Tune", "Color", "Grades shadows (Lift) and highlights (Gain) toward a colour",
            listOf(
                FxParamDef("Lift hue", 0f, 360f, 240f, "°", step = 3f),
                FxParamDef("Lift strength", 0f, 100f, 0f, "%"),
                FxParamDef("Lift luminance", -100f, 100f, 0f, "%"),
                FxParamDef("Gain hue", 0f, 360f, 36f, "°", step = 3f),
                FxParamDef("Gain strength", 0f, 100f, 0f, "%"),
                FxParamDef("Gain luminance", -100f, 100f, 0f, "%")
            )
        ),
        // Solid Color: values [0..2] the colour 0..255, [3] the strength %. Value [4] (the keyframed
        // flag) is the importer's, like Long Shadow's colour slot.
        FxDef(
            SOLID_COLOR, 61, "Solid Color", "Color", "Replaces the layer's colours with one colour, keeping its shape",
            listOf(
                FxParamDef("Red", 0f, 255f, 255f),
                FxParamDef("Green", 0f, 255f, 255f),
                FxParamDef("Blue", 0f, 255f, 255f),
                FxParamDef("Strength", 0f, 100f, 100f, "%")
            )
        ),
        // Alight Mirror: values [0] the flip (0 = horizontal, 1 = vertical), [1] the blend mode
        // (0..4, AM's order: Normal / Multiply / Screen / Over / Under), [2] the alpha %,
        // [3] the offset % added to the flipped coordinate. A keyframed alpha track may ride
        // from index 7 (the importer's; AmFxEval resolves [2] per frame).
        FxDef(
            AM_MIRROR, 62, "Mirror (Alight)", "Distort", "The whole layer flipped and blended over itself (Alight Motion's Mirror)",
            listOf(
                FxParamDef("Flip", 0f, 1f, 0f, "", step = 1f),
                FxParamDef("Blend mode", 0f, 4f, 0f, "", step = 1f),
                FxParamDef("Alpha", 0f, 100f, 100f, "%"),
                FxParamDef("Offset", -200f, 200f, 0f, "%")
            )
        ),
        // Glow Scan: values [0] threshold %, [1] radius %, [2] power (AM's "Feather"),
        // [3] smoothing % (AM's strength, the pre-blur), [4] fill %, [5] alpha %. Value [6]
        // (the glow colour, packed 0xRRGGBB) is written by the Alight importer — and by
        // [create] below with AM's default #0088FF — and survives panel edits like every
        // value past the declared params. A keyframed threshold track may ride from index 7.
        FxDef(
            GLOW_SCAN, 63, "Glow Scan", "Light", "A glowing band sweeps the layer's brightness (Alight Motion's Glow Scan)",
            listOf(
                FxParamDef("Threshold", 0f, 100f, 50f, "%"),
                FxParamDef("Radius", 0f, 100f, 10f, "%"),
                FxParamDef("Power", 0f, 4f, 2f, "", step = 0.05f),
                FxParamDef("Smoothing", 0f, 200f, 5f, "%"),
                FxParamDef("Fill", 0f, 100f, 100f, "%"),
                FxParamDef("Alpha", 0f, 100f, 100f, "%")
            )
        ),
        // Luma Key: values [0] low threshold %, [1] high threshold %, [2] feather %,
        // [3] invert, [4] weighted luma. A keyframed low threshold may ride from index 7.
        FxDef(
            LUMA_KEY, 64, "Luma Key", "Keying", "Makes the layer transparent by brightness (Alight Motion's Luma Key)",
            listOf(
                FxParamDef("Low threshold", 0f, 100f, 0f, "%"),
                FxParamDef("High threshold", 0f, 100f, 100f, "%"),
                FxParamDef("Feather", 0f, 100f, 5f, "%"),
                FxParamDef("Invert", 0f, 1f, 0f, "", step = 1f),
                FxParamDef("Weighted luma", 0f, 1f, 1f, "", step = 1f)
            )
        ),
        // Four Colour Gradient: values [0..3] the colours packed 0xRRGGBB (shown as numbers,
        // the Solid Color R/G/B pattern), [4] blend %, [5] alpha %, [6] blend mode. The four
        // anchor points ride the EXT values [7..10] (see [create]).
        FxDef(
            FOUR_COLOR_GRADIENT, 65, "Four Color Gradient", "Color", "Four colours blended from four anchor points (Alight Motion)",
            listOf(
                FxParamDef("Colour 1", 0f, 16777215f, 0xFF0000.toFloat(), "", step = 1f),
                FxParamDef("Colour 2", 0f, 16777215f, 0x00FF00.toFloat(), "", step = 1f),
                FxParamDef("Colour 3", 0f, 16777215f, 0x0000FF.toFloat(), "", step = 1f),
                FxParamDef("Colour 4", 0f, 16777215f, 0xFF00FF.toFloat(), "", step = 1f),
                FxParamDef("Blend", 25f, 200f, 100f, "%"),
                FxParamDef("Alpha", 0f, 100f, 75f, "%"),
                FxParamDef("Blend mode", 0f, 2f, 0f, "", step = 1f)
            )
        ),
        // Turbulent Displace (Alight): values [0] intensity %, [1] scale %, [2] direction,
        // [3] evolution %, [4] octaves, [5] screen space, [6] offset %. The EXT values
        // [7..13] (stretch / seed / position / parallax) are the importer's (and AM's
        // defaults via [create]); keyframed intensity / scale tails may ride from index 14.
        FxDef(
            TURBULENT_DISPLACE_AM, 67, "Turbulent Displace (Alight)", "Distort", "Simplex-noise displacement (Alight Motion's, deprecated there)",
            listOf(
                FxParamDef("Intensity", 0f, 250f, 25f, "%"),
                FxParamDef("Scale", 1f, 5000f, 100f, "%"),
                FxParamDef("Direction", 0f, 3f, 3f, "", step = 1f),
                FxParamDef("Evolution", -5000f, 5000f, 0f, "%"),
                FxParamDef("Octaves", 1f, 9f, 6f, "", step = 1f),
                FxParamDef("Screen space", 0f, 1f, 0f, "", step = 1f),
                FxParamDef("Offset", -100f, 100f, 0f, "%")
            )
        ),
        // Swirl (Alight): values [0] strength %, [1] radius %, [2..3] centre (AM units),
        // [4] version (4 / 3). A keyframed strength may ride from index 7.
        FxDef(
            SWIRL_AM, 69, "Swirl (Alight)", "Distort", "Twists the picture around a centre (Alight Motion's Swirl)",
            listOf(
                FxParamDef("Strength", -50f, 50f, 10f, "%"),
                FxParamDef("Radius", 0f, 80f, 30f, "%"),
                FxParamDef("Centre X", -1000f, 1000f, 0f),
                FxParamDef("Centre Y", -1000f, 1000f, 0f),
                FxParamDef("Version", 3f, 4f, 4f, "", step = 1f)
            )
        ),
        // Stretch Segment: values [0] angle, [1] stretch (AM units), [2] offset (AM units),
        // [3] smooth %.
        FxDef(
            STRETCH_SEGMENT, 70, "Stretch Segment", "Distort", "A band of the picture stretched flat along an angle (Alight Motion)",
            listOf(
                FxParamDef("Angle", 0f, 360f, 0f, "°", step = 3f),
                FxParamDef("Stretch", 0f, 900f, 10f),
                FxParamDef("Offset", -2000f, 2000f, 0f),
                FxParamDef("Smooth", 0f, 100f, 0f, "%")
            )
        ),
        // Halftone Dots (Alight): values [0] amount, [1] strength %, [2] angle, [3] phase %,
        // [4..5] the two colours packed 0xRRGGBB.
        FxDef(
            HALFTONE_DOTS, 71, "Halftone Dots (Alight)", "Stylize", "Two-colour dot halftone (Alight Motion's Halftone Dots)",
            listOf(
                FxParamDef("Amount", 0f, 500f, 100f, "", step = 0.5f),
                FxParamDef("Strength", 0f, 100f, 50f, "%"),
                FxParamDef("Angle", 0f, 360f, 45f, "°", step = 3f),
                FxParamDef("Phase", -100f, 100f, 50f, "%"),
                FxParamDef("Colour 1", 0f, 16777215f, 0x113355.toFloat(), "", step = 1f),
                FxParamDef("Colour 2", 0f, 16777215f, 0xFFEEDD.toFloat(), "", step = 1f)
            )
        ),
        // Pixelate (Alight): values [0] size, [1..2] stretch x/y %, [3] angle, [4] vignette %,
        // [5] screen space, [6] threshold %. The EXT values [7..13] hold the saturation % and
        // the offset; a keyframed size tail may ride from index 14.
        FxDef(
            PIXELATE_AM, 72, "Pixelate (Alight)", "Stylize", "Blocks with AM's vignette darkening (Alight Motion's Pixelate)",
            listOf(
                FxParamDef("Size", 1f, 500f, 20f),
                FxParamDef("Stretch X", 10f, 1000f, 100f, "%"),
                FxParamDef("Stretch Y", 10f, 1000f, 100f, "%"),
                FxParamDef("Angle", 0f, 360f, 0f, "°", step = 3f),
                FxParamDef("Vignette", 0f, 100f, 0f, "%"),
                FxParamDef("Screen space", 0f, 1f, 0f, "", step = 1f),
                FxParamDef("Threshold", 0f, 100f, 0f, "%")
            )
        ),
        // Replace Colour: values [0..1] the old / new colours packed 0xRRGGBB, [2] threshold %,
        // [3] feather %, [4] alpha %, [5] lock luminance.
        FxDef(
            REPLACE_COLOR, 74, "Replace Color", "Color", "Swaps one colour for another, by YUV distance (Alight Motion)",
            listOf(
                FxParamDef("Old colour", 0f, 16777215f, 0xFF0000.toFloat(), "", step = 1f),
                FxParamDef("New colour", 0f, 16777215f, 0x0000FF.toFloat(), "", step = 1f),
                FxParamDef("Threshold", 0f, 400f, 25f, "%"),
                FxParamDef("Feather", 0f, 200f, 25f, "%"),
                FxParamDef("Alpha", 0f, 100f, 100f, "%"),
                FxParamDef("Lock luminance", 0f, 1f, 0f, "", step = 1f)
            )
        ),
        // Chroma Key (Alight): values [0] the key colour packed 0xRRGGBB, [1] threshold %,
        // [2] feather %, [3] defringe, [4] invert.
        FxDef(
            CHROMA_KEY_AM, 75, "Chroma Key (Alight)", "Keying", "Keys out a colour by YUV distance (Alight Motion's Chroma Key)",
            listOf(
                FxParamDef("Key colour", 0f, 16777215f, 0x000000.toFloat(), "", step = 1f),
                FxParamDef("Threshold", 0f, 100f, 10f, "%"),
                FxParamDef("Feather", 0f, 100f, 5f, "%"),
                FxParamDef("Defringe", 0f, 1f, 0f, "", step = 1f),
                FxParamDef("Invert", 0f, 1f, 0f, "", step = 1f)
            )
        ),
        // Colour Balance (Alight): values [0..6] the low / mid / crHigh band values; the EXT
        // values [7..9] hold mgHigh / ybHigh / fix luminance (see [create]).
        FxDef(
            COLOR_BALANCE_AM, 76, "Color Balance (Alight)", "Color", "Per-band channel gains by luminance (Alight Motion's Color Balance)",
            listOf(
                FxParamDef("Cyan/red low", -100f, 100f, 0f),
                FxParamDef("Magenta/green low", -100f, 100f, 0f),
                FxParamDef("Yellow/blue low", -100f, 100f, 0f),
                FxParamDef("Cyan/red mid", -100f, 100f, 0f),
                FxParamDef("Magenta/green mid", -100f, 100f, 0f),
                FxParamDef("Yellow/blue mid", -100f, 100f, 0f),
                FxParamDef("Cyan/red high", -100f, 100f, 0f)
            )
        ),
        // Colour Temperature (Alight): values [0] temperature (Kelvin / 1000), [1] strength %.
        FxDef(
            COLOR_TEMP_AM, 78, "Color Temperature (Alight)", "Color", "Warms or cools by Kelvin temperature (Alight Motion)",
            listOf(
                FxParamDef("Temperature", 1f, 40f, 6.5f, " kK", step = 0.01f),
                FxParamDef("Strength", 0f, 100f, 100f, "%")
            )
        ),
        // Colour Tint (Alight): values [0] tint hue deg, [1] strength %.
        FxDef(
            COLOR_TINT_AM, 79, "Color Tint (Alight)", "Color", "Pushes the chroma toward one hue (Alight Motion's Color Tint)",
            listOf(
                FxParamDef("Hue", -360f, 360f, 0f, "°", step = 1f),
                FxParamDef("Strength", 0f, 100f, 5f, "%")
            )
        ),
        // Spin Blur (Alight): values [0] angle deg, [1..2] the centre point (AM units).
        FxDef(
            SPIN_BLUR_AM, 80, "Spin Blur (Alight)", "Blur", "Rotational blur, AM's arc-length taps (Alight Motion's Spin Blur)",
            listOf(
                FxParamDef("Angle", 0f, 360f, 10f, "°", step = 0.1f),
                FxParamDef("Centre X", -1000f, 1000f, 0f),
                FxParamDef("Centre Y", -1000f, 1000f, 0f)
            )
        ),
        // Offset (Alight): values [0..1] offset x/y (AM units), [2] feather %, [3] mask.
        FxDef(
            OFFSET_AM, 81, "Offset (Alight)", "Distort", "Wrap-around shift of the layer over itself (Alight Motion's Offset)",
            listOf(
                FxParamDef("Offset X", -10000f, 10000f, 0f, step = 1f),
                FxParamDef("Offset Y", -10000f, 10000f, 0f, step = 1f),
                FxParamDef("Feather", 0f, 100f, 0f, "%"),
                FxParamDef("Mask", 0f, 1f, 0f, "", step = 1f)
            )
        ),
        // Radial Wipe (Alight): values [0] progress deg, [1] feather deg, [2] direction,
        // [3] repeat count, [4] the centre packed, [5] offset deg.
        FxDef(
            RADIAL_WIPE_AM, 82, "Radial Wipe (Alight)", "Stylize", "An angular wipe around a centre (Alight Motion's Radial Wipe)",
            listOf(
                FxParamDef("Progress", 0f, 360f, 360f, "°", step = 1f),
                FxParamDef("Feather", 0f, 360f, 0f, "°", step = 1f),
                FxParamDef("Direction", 0f, 2f, 0f, "", step = 1f),
                FxParamDef("Repeat", 1f, 36f, 1f, "", step = 1f),
                FxParamDef("Centre (packed)", 0f, 16777215f, 0x800800.toFloat(), "", step = 1f),
                FxParamDef("Offset", 0f, 360f, 0f, "°", step = 1f)
            )
        ),
        // Find Edges (Alight): values [0] smoothing, [1] threshold %, [2] invert.
        FxDef(
            FIND_EDGES_AM, 83, "Find Edges (Alight)", "Stylize", "Scharr edge gradient (Alight Motion's Find Edges)",
            listOf(
                FxParamDef("Smoothing", 0f, 10f, 1f, step = 0.1f),
                FxParamDef("Threshold", 0f, 100f, 0f, "%"),
                FxParamDef("Invert", 0f, 1f, 0f, "", step = 1f)
            )
        ),
        // Hex Shift (Alight): values [0] strength (AM raw), [1] size, [2..3] stretch %,
        // [4] angle deg, [5] seed, [6] screen space; EXT [7] mask to layer, [8] position packed.
        FxDef(
            HEX_SHIFT_AM, 84, "Hex Shift (Alight)", "Distort", "Hexagonal cells shift the picture randomly (Alight Motion's Hex Shift)",
            listOf(
                FxParamDef("Strength", 0f, 0.2f, 0.01f, step = 0.001f),
                FxParamDef("Size", 1f, 500f, 100f, step = 1f),
                FxParamDef("Stretch X", 1f, 1000f, 100f, "%"),
                FxParamDef("Stretch Y", 1f, 1000f, 100f, "%"),
                FxParamDef("Angle", 0f, 3600f, 0f, "°", step = 1f),
                FxParamDef("Seed", 0f, 5f, 0f, step = 0.01f),
                FxParamDef("Screen space", 0f, 1f, 0f, "", step = 1f)
            )
        ),
        // Soft Glow (Alight): values [0] strength %, [1] brightness %, [2] contrast %,
        // [3] highlights %, [4] the colour packed, [5] blend %, [6] alpha %; EXT [7] outside %.
        FxDef(
            SOFT_GLOW_AM, 86, "Soft Glow (Alight)", "Light", "A soft screen-blended glow from the blurred layer (Alight Motion)",
            listOf(
                FxParamDef("Strength", 0f, 100f, 50f, "%"),
                FxParamDef("Brightness", 0f, 200f, 100f, "%"),
                FxParamDef("Contrast", 0f, 100f, 70f, "%"),
                FxParamDef("Highlights", 0f, 100f, 100f, "%"),
                FxParamDef("Colour", 0f, 16777215f, 0xFF5566.toFloat(), "", step = 1f),
                FxParamDef("Blend", 0f, 100f, 25f, "%"),
                FxParamDef("Alpha", 0f, 100f, 100f, "%")
            )
        ),
        // Fill Behind (Alight): values [0] strength %, [1] feather %, [2] gamma x100,
        // [3] tint strength %, [4] tint colour packed, [5] edge strength %, [6] scan lines %;
        // EXT [7..13] as on the const.
        FxDef(
            FILL_BEHIND_AM, 88, "Fill Behind (Alight)", "Backdrop", "The blurred layer fills behind itself (Alight Motion's Fill Behind)",
            listOf(
                FxParamDef("Strength", 0f, 100f, 8f, "%"),
                FxParamDef("Feather", 0f, 100f, 5f, "%"),
                FxParamDef("Gamma", 10f, 400f, 100f, "%"),
                FxParamDef("Tint strength", 0f, 100f, 0f, "%"),
                FxParamDef("Tint colour", 0f, 16777215f, 0xFF5566.toFloat(), "", step = 1f),
                FxParamDef("Edge strength", 0f, 100f, 0f, "%"),
                FxParamDef("Scan lines", 0f, 100f, 0f, "%")
            )
        ),
        // ---- v165: Alight tier 3 (values as on the consts) ----
        FxDef(
            WAVE_WARP_AM, 90, "Wave Warp (Alight)", "Distort", "A sine wave displaces the layer (Alight Motion's Wave Warp 2)",
            listOf(
                FxParamDef("Phase", 0f, 500f, 0f, "", step = 0.25f),
                FxParamDef("Angle", -1800f, 1800f, 0f, "°", step = 3f),
                FxParamDef("Spacing", 0f, 500f, 20f, "", step = 0.5f),
                FxParamDef("Magnitude", 0f, 30f, 4f, "", step = 0.5f),
                FxParamDef("Warp angle", -180f, 180f, 90f, "°", step = 3f),
                FxParamDef("Damping", -1f, 1f, 0f, "", step = 0.05f),
                FxParamDef("Damping spacing", -1f, 1f, 0f, "", step = 0.05f)
            )
        ),
        FxDef(
            CIRCULAR_RIPPLE_AM, 92, "Circular Ripple (Alight)", "Distort", "A radial sine ripple around a centre (Alight Motion)",
            listOf(
                FxParamDef("Strength", -1f, 1f, 0.025f, "", step = 0.005f),
                FxParamDef("Radius", 0f, 0.8f, 0.3f, "", step = 0.01f),
                FxParamDef("Frequency", 0f, 100f, 20f, "", step = 0.5f),
                FxParamDef("Feather", 0f, 1f, 0.1f, "", step = 0.01f),
                FxParamDef("Phase", -1000f, 1000f, 0f, "", step = 0.25f),
                FxParamDef("Centre X", -1000f, 1000f, 0f, "", step = 5f),
                FxParamDef("Centre Y", -1000f, 1000f, 0f, "", step = 5f)
            )
        ),
        FxDef(
            STRETCH_AM, 93, "Stretch (Alight)", "Distort", "Scales the layer along a rotated axis (Alight Motion's Stretch)",
            listOf(
                FxParamDef("Scale", 0.01f, 50f, 1f, "", step = 0.01f),
                FxParamDef("Angle", 0f, 3600f, 0f, "°", step = 3f),
                FxParamDef("Content only", 0f, 1f, 0f, "", step = 1f),
                FxParamDef("Version", 1f, 2f, 2f, "", step = 1f)
            )
        ),
        FxDef(
            SQUEEZE_AM, 94, "Squeeze (Alight)", "Distort", "Pinches the layer on one axis, bulges it on the other (Alight Motion)",
            listOf(FxParamDef("Strength", -2f, 2f, 0f, "", step = 0.05f))
        ),
        FxDef(
            FRACTAL_WARP_AM, 95, "Fractal Warp (Alight)", "Distort", "Value-noise fbm displaces the layer (Alight Motion's Fractal Warp)",
            listOf(
                FxParamDef("Magnitude", -5f, 5f, 0.2f, "", step = 0.05f),
                FxParamDef("Scale", 0.1f, 4f, 1f, "", step = 0.05f),
                FxParamDef("Intensity", 0.25f, 0.75f, 0.5f, "", step = 0.01f),
                FxParamDef("Octaves", 1f, 9f, 6f, "", step = 1f),
                FxParamDef("Offset X", -2000f, 2000f, 0f, "", step = 5f),
                FxParamDef("Offset Y", -2000f, 2000f, 0f, "", step = 5f),
                FxParamDef("Parallax X", -2000f, 2000f, 0f, "", step = 5f)
            )
        ),
        FxDef(
            STRIPES_AM, 97, "Stripes (Alight)", "Stylize", "Rotated colour bands over the layer (Alight Motion's Stripes)",
            listOf(
                FxParamDef("Amount", 0.5f, 200f, 10f, "", step = 0.5f),
                FxParamDef("Width", 0f, 1f, 0.5f, "", step = 0.01f),
                FxParamDef("Angle", 0f, 3600f, 45f, "°", step = 3f),
                FxParamDef("Phase", 0f, 1000f, 0f, "", step = 0.25f),
                FxParamDef("Colour", 0f, 16777215f, 0x2D1EF6.toFloat(), "", step = 1f),
                FxParamDef("Smoothing", 0f, 0.5f, 0.01f, "", step = 0.01f),
                FxParamDef("Punch out", 0f, 1f, 0f, "", step = 1f)
            )
        ),
        FxDef(
            TURBULENT_DISPLACE3_AM, 98, "Turbulent Displace 3 (Alight)", "Distort", "Simplex turbulence displaces the layer (Alight Motion)",
            listOf(
                FxParamDef("Intensity", 0f, 2.5f, 0.25f, "", step = 0.05f),
                FxParamDef("Scale", 0.01f, 50f, 1f, "", step = 0.01f),
                FxParamDef("Evolution", -50f, 50f, 0f, "", step = 0.25f),
                FxParamDef("Seed", 0f, 5f, 0f, "", step = 0.05f),
                FxParamDef("Direction", 0f, 5f, 4f, "", step = 1f),
                FxParamDef("Octaves", 1f, 9f, 6f, "", step = 1f),
                FxParamDef("Offset", -1f, 1f, 0f, "", step = 0.01f)
            )
        ),
        FxDef(
            ROUGHEN_EDGES_AM, 100, "Roughen Edges (Alight)", "Stylize", "Turbulence erodes the layer's edges (Alight Motion)",
            listOf(
                FxParamDef("Strength", 0f, 2.5f, 0.2f, "", step = 0.05f),
                FxParamDef("Detail", 0.01f, 50f, 1f, "", step = 0.01f),
                FxParamDef("Evolution", -50f, 50f, 0f, "", step = 0.25f),
                FxParamDef("Seed", 0f, 5f, 0f, "", step = 0.05f),
                FxParamDef("Octaves", 1f, 9f, 6f, "", step = 1f)
            )
        ),
        FxDef(
            STREAKS_AM, 101, "Streaks (Alight)", "Blur", "A directional multi-tap smear (Alight Motion's Streaks)",
            listOf(
                FxParamDef("Strength", 0f, 1f, 0.15f, "", step = 0.01f),
                FxParamDef("Angle", 0f, 3600f, 0f, "°", step = 3f),
                FxParamDef("Bias", -1f, 1f, 0f, "", step = 0.05f),
                FxParamDef("Alpha", 0f, 1f, 1f, "", step = 0.01f),
                FxParamDef("Red mode", 0f, 3f, 1f, "", step = 1f),
                FxParamDef("Green mode", 0f, 3f, 1f, "", step = 1f),
                FxParamDef("Blue mode", 0f, 3f, 1f, "", step = 1f)
            )
        ),
        FxDef(
            COLOR_HOT_AM, 103, "Colour Hot (Alight)", "Color", "Luminance remapped between two hue discs (Alight Motion)",
            listOf(
                FxParamDef("Colour hue", 0f, 360f, 0f, "°", step = 3f),
                FxParamDef("Colour saturation", 0f, 1f, 1f, "", step = 0.01f),
                FxParamDef("Colour bias", -1f, 1f, 0f, "", step = 0.05f),
                FxParamDef("Tint hue", 0f, 360f, 60f, "°", step = 3f),
                FxParamDef("Tint saturation", 0f, 1f, 1f, "", step = 0.01f),
                FxParamDef("Tint bias", -1f, 1f, 0f, "", step = 0.05f)
            )
        ),
        FxDef(
            COLORIZE_AM, 104, "Colorize (Alight)", "Color", "Pushes the chroma toward a tint (Alight Motion's Colorize)",
            listOf(
                FxParamDef("Tint hue", 0f, 360f, 0f, "°", step = 3f),
                FxParamDef("Amount", 0f, 1f, 0f, "", step = 0.01f)
            )
        ),
        FxDef(
            HIGHLIGHT_SHADOW_AM, 105, "Highlights & Shadows (Alight)", "Color", "GPUImage-style highlight / shadow lift (Alight Motion)",
            listOf(
                FxParamDef("Shadows", 0f, 2f, 1f, "", step = 0.01f),
                FxParamDef("Highlights", 0f, 2f, 1f, "", step = 0.01f)
            )
        ),
        FxDef(
            SPOT_COLOR_AM, 106, "Spot Colour (Alight)", "Color", "Keeps one colour, desaturates the rest (Alight Motion)",
            listOf(
                FxParamDef("Colour", 0f, 16777215f, 0xFF0000.toFloat(), "", step = 1f),
                FxParamDef("Threshold", 0f, 2f, 0.35f, "", step = 0.01f),
                FxParamDef("Feather", 0f, 2f, 0.25f, "", step = 0.01f),
                FxParamDef("Saturation", 0f, 1f, 0f, "", step = 0.01f),
                FxParamDef("Choke", 0.5f, 20f, 5f, "", step = 0.1f)
            )
        ),
        FxDef(
            DISSOLVE_AM, 107, "Dissolve (Alight)", "Stylize", "A per-pixel hash dissolves the layer (Alight Motion)",
            listOf(
                FxParamDef("Amount", 0f, 1f, 0.75f, "", step = 0.01f),
                FxParamDef("Seed", 0f, 5f, 0f, "", step = 0.05f)
            )
        ),
        FxDef(
            SIMPLE_STARS_AM, 108, "Simple Stars (Alight)", "Stylize", "Procedural star layers over the layer (Alight Motion)",
            listOf(
                FxParamDef("Scale", 0.01f, 50f, 1f, "", step = 0.01f),
                FxParamDef("Star size", 0f, 5f, 0.1f, "", step = 0.05f),
                FxParamDef("Star feather", 0f, 20f, 1f, "", step = 0.1f),
                FxParamDef("Colour", 0f, 16777215f, 0xFFFFFF.toFloat(), "", step = 1f),
                FxParamDef("Star count", 1f, 50f, 25f, "", step = 1f),
                FxParamDef("Brightness", 0f, 5f, 1f, "", step = 0.05f),
                FxParamDef("Blend mode", 0f, 3f, 2f, "", step = 1f)
            )
        ),
        FxDef(
            DOTS_AM, 110, "Dots (Alight)", "Stylize", "A stepped Voronoi dot field over the layer (Alight Motion's Dots)",
            listOf(
                FxParamDef("Scale", 0.01f, 100f, 1f, "", step = 0.01f),
                FxParamDef("Phase", -99f, 99f, 0f, "", step = 0.25f),
                FxParamDef("Radius", 0f, 2f, 0.2f, "", step = 0.01f),
                FxParamDef("Feather", 0f, 1f, 0.1f, "", step = 0.01f),
                FxParamDef("Colour", 0f, 16777215f, 0xFFFFFF.toFloat(), "", step = 1f),
                FxParamDef("Colour 2", 0f, 16777215f, 0f, "", step = 1f),
                FxParamDef("Density", 1f, 13f, 12f, "", step = 1f)
            )
        ),
        FxDef(
            RIDGES_AM, 112, "Ridges (Alight)", "Stylize", "A ridged fractal field between two colours (Alight Motion)",
            listOf(
                FxParamDef("Intensity", 0.1f, 1.5f, 1f, "", step = 0.01f),
                FxParamDef("Scale", 0.01f, 50f, 1f, "", step = 0.01f),
                FxParamDef("Evolution", -50f, 50f, 0f, "", step = 0.25f),
                FxParamDef("Seed", 0f, 5f, 0f, "", step = 0.05f),
                FxParamDef("Octaves", 1f, 9f, 6f, "", step = 1f),
                FxParamDef("Colour 1", 0f, 16777215f, 0f, "", step = 1f),
                FxParamDef("Colour 2", 0f, 16777215f, 0xFFFFFF.toFloat(), "", step = 1f)
            )
        ),
        FxDef(
            BEND_WARP_AM, 114, "Bend Warp (Alight)", "Distort", "Bends the layer past a line along an arc (Alight Motion)",
            listOf(
                FxParamDef("Amount", -180f, 180f, 0f, "°", step = 1f),
                FxParamDef("Offset", 0f, 1f, 0f, "", step = 0.01f),
                FxParamDef("Origin", 0f, 3f, 3f, "", step = 1f),
                FxParamDef("Opposite", 0f, 2f, 0f, "", step = 1f),
                FxParamDef("High limit", 0f, 1f, 0.75f, "", step = 0.01f),
                FxParamDef("Low limit", 0f, 1f, 0.25f, "", step = 0.01f)
            )
        ),
        FxDef(
            DISPLACE_MAP_AM, 115, "Displace Map (Alight)", "Backdrop", "The layer's channels displace the backdrop (Alight Motion)",
            listOf(
                FxParamDef("Offset X", -1000f, 1000f, 0f, "", step = 1f),
                FxParamDef("Offset Y", -1000f, 1000f, 0f, "", step = 1f),
                FxParamDef("X source", 0f, 3f, 1f, "", step = 1f),
                FxParamDef("Y source", 0f, 3f, 2f, "", step = 1f),
                FxParamDef("Edges", 0f, 3f, 1f, "", step = 1f),
                FxParamDef("From centre", 0f, 1f, 0f, "", step = 1f),
                FxParamDef("Invert", 0f, 1f, 0f, "", step = 1f)
            )
        ),
        FxDef(
            DISPLACE_MAP_RAD_AM, 116, "Displace Map Radial (Alight)", "Backdrop", "Rotates / pushes the backdrop radially (Alight Motion)",
            listOf(
                FxParamDef("Angle", -3600f, 3600f, 0f, "°", step = 3f),
                FxParamDef("Radius", -1000f, 1000f, 0f, "", step = 1f),
                FxParamDef("X source", 0f, 3f, 1f, "", step = 1f),
                FxParamDef("Y source", 0f, 3f, 2f, "", step = 1f),
                FxParamDef("Edges", 0f, 3f, 1f, "", step = 1f),
                FxParamDef("From centre", 0f, 1f, 0f, "", step = 1f),
                FxParamDef("Invert", 0f, 1f, 0f, "", step = 1f)
            )
        ),
        FxDef(
            SMOOTH_BEVEL_AM, 118, "Smooth Bevel (Alight)", "Stylize", "A distance-field bevel with two lights (Alight Motion)",
            listOf(
                FxParamDef("Radius", 0f, 200f, 44f, "", step = 1f),
                FxParamDef("Diffuse angle", 0f, 360f, 45f, "°", step = 3f),
                FxParamDef("Specular angle", 0f, 360f, 45f, "°", step = 3f),
                FxParamDef("Diffuse intensity", 0f, 1f, 0.5f, "", step = 0.01f),
                FxParamDef("Specular intensity", 0f, 1f, 1f, "", step = 0.01f),
                FxParamDef("Diffuse elevation", 0f, 5f, 0f, "", step = 0.05f),
                FxParamDef("Specular elevation", 0f, 5f, 2.2f, "", step = 0.05f)
            )
        ),
        FxDef(
            LIGHT_GLOW_AM, 120, "Light Glow (Alight)", "Light", "A threshold glow with a halo (Alight Motion's Light Glow)",
            listOf(
                FxParamDef("Strength", 0f, 2f, 0.25f, "", step = 0.01f),
                FxParamDef("Threshold", 0f, 1f, 0.7f, "", step = 0.01f),
                FxParamDef("Intensity", 0f, 5f, 1f, "", step = 0.05f),
                FxParamDef("Colour", 0f, 16777215f, 0xFF5566.toFloat(), "", step = 1f),
                FxParamDef("Blend", 0f, 1f, 0.25f, "", step = 0.01f),
                FxParamDef("Halo", 0f, 5f, 0f, "", step = 0.05f),
                FxParamDef("Alpha", 0f, 1f, 0.75f, "", step = 0.01f)
            )
        ),
        FxDef(
            INNER_GLOW_AM, 121, "Inner Glow (Alight)", "Light", "A glow inward from the layer's edges (Alight Motion's Inner Glow)",
            listOf(
                FxParamDef("Radius", 0f, 400f, 50f, "", step = 1f),
                FxParamDef("Alpha", 0f, 1f, 0.75f, "", step = 0.01f),
                FxParamDef("Hardness", 0f, 1f, 0.2f, "", step = 0.01f),
                FxParamDef("Colour", 0f, 16777215f, 0xFFFFFF.toFloat(), "", step = 1f),
                FxParamDef("Blend mode", 0f, 2f, 2f, "", step = 1f)
            )
        ),
        // Dark Glow (Alight): values as on the const.
        FxDef(
            DARK_GLOW_AM, 122, "Dark Glow (Alight)", "Light", "The layer's dark parts, blurred and multiplied back over it (Alight Motion's Dark Glow)",
            listOf(
                FxParamDef("Strength", 0f, 200f, 25f, "%"),
                FxParamDef("Threshold", 0f, 100f, 30f, "%"),
                FxParamDef("Colour", 0f, 16777215f, 0xFF5566.toFloat(), "", step = 1f),
                FxParamDef("Blend", 0f, 100f, 25f, "%"),
                FxParamDef("Alpha", 0f, 100f, 75f, "%")
            )
        ),
        // Extrude (Alight): values as on the const. The panel edits the march's own
        // params; the EXT / EXT2 values (rotate, position, colours, light) are AM's
        // defaults via [extraValues] and survive panel edits.
        FxDef(
            EXTRUDE_AM, 123, "Extrude (Alight)", "Distort", "The layer extruded into a 3D box, raymarched (Alight Motion's S3D Extrude)",
            listOf(
                FxParamDef("Depth", 0f, 10f, 0.1f, "", step = 0.01f),
                FxParamDef("Scale", 0.05f, 10f, 1f, "", step = 0.01f),
                FxParamDef("Width ratio", 0.01f, 10f, 1f, "", step = 0.01f),
                FxParamDef("Height ratio", 0.01f, 10f, 1f, "", step = 0.01f),
                FxParamDef("Interior blend", 0f, 1f, 0f, "", step = 0.01f),
                FxParamDef("Fade blend", 0f, 1f, 0f, "", step = 0.01f),
                FxParamDef("Shading", 0f, 5f, 1f, "", step = 1f)
            )
        ),
        // Extrude Flat (Alight): values as on the const.
        FxDef(
            EXTRUDE_FLAT_AM, 126, "Extrude Flat (Alight)", "Distort", "The layer extruded flat, no lighting (Alight Motion's Extrude Flat)",
            listOf(
                FxParamDef("Depth", 0f, 10f, 0.05f, "", step = 0.005f),
                FxParamDef("Scale", 0.05f, 10f, 1f, "", step = 0.01f),
                FxParamDef("Stretch X", 0.01f, 10f, 1f, "", step = 0.01f),
                FxParamDef("Stretch Y", 0.01f, 10f, 1f, "", step = 0.01f),
                FxParamDef("Interior blend", 0f, 1f, 1f, "", step = 0.01f),
                FxParamDef("Fade blend", 0f, 1f, 1f, "", step = 0.01f),
                FxParamDef("Interior on", 0f, 1f, 1f, "", step = 1f)
            )
        ),
        // Edge Glow (Alight): values as on the const. The panel edits the seven primary
        // slots; gamma / brightness / tint ride the EXT group from AM's def defaults
        // (extraValues) at create time and are imported verbatim.
        FxDef(
            EDGE_GLOW_AM, 129, "Edge Glow (Alight)", "Light", "The layer's edges, blurred and composited back over it (Alight Motion's Edge Glow)",
            listOf(
                FxParamDef("Smoothing", 1f, 10f, 5f, "", step = 0.01f),
                FxParamDef("Threshold", 0f, 1f, 0.7f, "", step = 0.01f),
                FxParamDef("Invert", 0f, 1f, 0f, "", step = 1f),
                FxParamDef("Blur", 0f, 2f, 0.1f, "", step = 0.01f),
                FxParamDef("Spread", 0f, 3f, 1.5f, "", step = 0.01f),
                FxParamDef("Fill colour", 0f, 16777215f, 0x0088FF.toFloat(), "", step = 1f),
                FxParamDef("Blend", 0f, 2f, 0f, "", step = 0.01f)
            )
        ),
        // ---- v175: Alight's effects tier 4 (values as on the consts) ----
        FxDef(
            THRESHOLD_AM, 131, "Threshold (Alight)", "Color", "A luma threshold mask, multiplied or screened (Alight Motion's Threshold)",
            listOf(
                FxParamDef("Threshold", 0f, 1f, 0.5f, "", step = 0.01f),
                FxParamDef("Feather", 0f, 1f, 0f, "", step = 0.01f),
                FxParamDef("Invert", 0f, 1f, 0f, "", step = 1f),
                FxParamDef("Blend", 0f, 2f, 0f, "", step = 1f)
            )
        ),
        FxDef(
            SPECTRALMAP_AM, 132, "Spectral Map (Alight)", "Color", "A rainbow ramp over the brightness (Alight Motion's Spectral Map)",
            listOf(
                FxParamDef("Phase", -99f, 99f, 0f, "", step = 0.01f),
                FxParamDef("Repeat", 0.1f, 10f, 1f, "", step = 0.01f),
                FxParamDef("Alpha", 0f, 1f, 1f, "", step = 0.01f),
                FxParamDef("Squeeze", 0f, 1f, 0f, "", step = 0.01f),
                FxParamDef("Saturation", -1f, 1f, 0f, "", step = 0.01f),
                FxParamDef("Reverse", 0f, 1f, 0f, "", step = 1f),
                FxParamDef("Scale brightness", 0f, 1f, 0f, "", step = 1f)
            )
        ),
        FxDef(
            CHANNELMAP_AM, 134, "Channel Map (Alight)", "Color", "Re-route the colour channels (Alight Motion's Channel Map)",
            listOf(
                FxParamDef("Red from", 0f, 6f, 0f, "", step = 1f),
                FxParamDef("Green from", 0f, 6f, 1f, "", step = 1f),
                FxParamDef("Blue from", 0f, 6f, 2f, "", step = 1f),
                FxParamDef("Alpha from", 0f, 6f, 3f, "", step = 1f)
            )
        ),
        FxDef(
            HALFTONE_LINES_AM, 135, "Halftone Lines (Alight)", "Stylize", "The picture re-drawn as two-colour bands (Alight Motion's Halftone Lines)",
            listOf(
                FxParamDef("Amount", 0f, 500f, 100f, "", step = 0.1f),
                FxParamDef("Strength", 0f, 1f, 0.5f, "", step = 0.01f),
                FxParamDef("Angle", -3600f, 3600f, 45f, "°", step = 0.1f),
                FxParamDef("Phase", -1000f, 1000f, 0f, "", step = 0.01f),
                FxParamDef("Colour 1", 0f, 16777215f, 0x113355.toFloat(), "", step = 1f),
                FxParamDef("Colour 2", 0f, 16777215f, 0xFFEEDD.toFloat(), "", step = 1f)
            )
        ),
        FxDef(
            TURBULENCE2_AM, 136, "Turbulence (Alight)", "Stylize", "A two-colour cloud field over the layer (Alight Motion's Turbulence)",
            listOf(
                FxParamDef("Intensity", 0.1f, 1.5f, 1f, "", step = 0.01f),
                FxParamDef("Scale", 0.01f, 50f, 1f, "", step = 0.01f),
                FxParamDef("Evolution", -50f, 50f, 0f, "", step = 0.01f),
                FxParamDef("Seed", 0f, 5f, 0f, "", step = 0.01f),
                FxParamDef("Colour 1", 0f, 16777215f, 0f, "", step = 1f),
                FxParamDef("Colour 2", 0f, 16777215f, 0xFFFFFF.toFloat(), "", step = 1f),
                FxParamDef("Blend", 0f, 2f, 0f, "", step = 1f)
            )
        ),
        FxDef(
            CHECKER2_AM, 139, "Checker (Alight)", "Stylize", "A checkerboard of one colour over the layer (Alight Motion's Checker)",
            listOf(
                FxParamDef("Amount", 0.5f, 200f, 10f, "", step = 0.1f),
                FxParamDef("Angle", -3600f, 3600f, 0f, "°", step = 0.1f),
                FxParamDef("Colour", 0f, 16777215f, 0x2D1EF6.toFloat(), "", step = 1f),
                FxParamDef("Punch out", 0f, 1f, 0f, "", step = 1f),
                FxParamDef("Screen space", 0f, 1f, 0f, "", step = 1f),
                FxParamDef("Position X", -2048f, 2047f, 0f, "", step = 1f),
                FxParamDef("Position Y", -2048f, 2047f, 0f, "", step = 1f)
            )
        ),
        FxDef(
            MOSAIC_AM, 141, "Mosaic (Alight)", "Stylize", "The picture averaged into tiles (Alight Motion's Mosaic)",
            listOf(
                FxParamDef("Size", 1f, 500f, 10f, "", step = 0.1f),
                FxParamDef("Phase X", -2048f, 2047f, 0f, "", step = 1f),
                FxParamDef("Phase Y", -2048f, 2047f, 0f, "", step = 1f)
            )
        ),
        FxDef(
            GLOW_AM, 142, "Glow (Alight)", "Light", "A coloured halo outside the layer's silhouette (Alight Motion's Glow)",
            listOf(
                FxParamDef("Radius", 0f, 400f, 100f, "", step = 0.1f),
                FxParamDef("Alpha", 0f, 1f, 0.75f, "", step = 0.01f),
                FxParamDef("Hardness", 0f, 1f, 0.5f, "", step = 0.01f),
                FxParamDef("Colour", 0f, 16777215f, 0xFFFFFF.toFloat(), "", step = 1f),
                FxParamDef("Colour alpha", 0f, 1f, 0.67f, "", step = 0.01f),
                FxParamDef("Blend", 0f, 2f, 2f, "", step = 1f)
            )
        ),
        FxDef(
            INNER_BLUR_AM, 143, "Inner Blur (Alight)", "Blur", "The layer's own colours blurred inside its silhouette (Alight Motion's Inner Blur)",
            listOf(
                FxParamDef("Strength", 0f, 1f, 0.15f, "", step = 0.01f),
                FxParamDef("Iterations", 1f, 6f, 2f, "", step = 1f)
            )
        ),
        FxDef(
            BOX_BLUR3_AM, 144, "Box Blur 3 (Alight)", "Blur", "A wide box blur at half resolution (Alight Motion's Box Blur 3)",
            listOf(
                FxParamDef("Strength", 0f, 2f, 0.15f, "", step = 0.01f)
            )
        ),
        FxDef(
            FEATHER_AM, 145, "Feather (Alight)", "Blur", "The layer's edges feathered away (Alight Motion's Feather)",
            listOf(
                FxParamDef("Strength", 0f, 2f, 0.15f, "", step = 0.01f),
                FxParamDef("Iterations", 1f, 5f, 3f, "", step = 1f)
            )
        ),
        FxDef(
            ELECTRIC_EDGES_AM, 146, "Electric Edges (Alight)", "Stylize", "Crackling electric outlines along the layer's edges (Alight Motion's Electric Edges)",
            listOf(
                FxParamDef("Distance", 0f, 1f, 0.3f, "", step = 0.01f),
                FxParamDef("Thickness", 0f, 1f, 0.8f, "", step = 0.01f),
                FxParamDef("Complexity", 0f, 10f, 2f, "", step = 0.01f),
                FxParamDef("Disort", 0f, 2f, 0.5f, "", step = 0.01f),
                FxParamDef("Colour 1", 0f, 16777215f, 0xFF77FF.toFloat(), "", step = 1f),
                FxParamDef("Colour 2", 0f, 16777215f, 0xDDBBAA.toFloat(), "", step = 1f),
                FxParamDef("Alpha", 0f, 1f, 1f, "", step = 0.01f)
            )
        ),
        FxDef(
            SMOOTH_EDGES_AM, 149, "Smooth Edges (Alight)", "Stylize", "The silhouette smoothed and re-cut (Alight Motion's Smooth Edges)",
            listOf(
                FxParamDef("Strength", 0f, 2f, 0.02f, "", step = 0.01f),
                FxParamDef("Mask to layer", 0f, 1f, 0f, "", step = 1f)
            )
        ),
        FxDef(
            RADIAL_BLUR, 8, "Radial Blur", "Blur", "Spin or zoom blur around a centre",
            listOf(
                FxParamDef("Amount", 0f, 100f, 30f),
                FxParamDef("Spin / Zoom", 0f, 100f, 100f, "%"),
                FxParamDef("Centre X", 0f, 100f, 50f, "%"),
                FxParamDef("Centre Y", 0f, 100f, 50f, "%")
            )
        ),
        // ---- v140: colour ----
        FxDef(
            EXPOSURE, 10, "Exposure", "Color", "Stops, offset and gamma in linear light",
            listOf(
                FxParamDef("Exposure", -5f, 5f, 0f, " EV", step = 0.1f),
                FxParamDef("Offset", -100f, 100f, 0f),
                FxParamDef("Gamma", 20f, 300f, 100f, "%"),
                FxParamDef("Highlight rolloff", 0f, 100f, 0f, "%")
            )
        ),
        FxDef(
            BRIGHT_CONTRAST, 11, "Brightness & Contrast", "Color", "Lighter, darker, punchier",
            listOf(
                FxParamDef("Brightness", -100f, 100f, 0f),
                FxParamDef("Contrast", -100f, 100f, 0f)
            )
        ),
        FxDef(
            HUE_SAT, 12, "Hue / Saturation", "Color", "Turn the colours, boost or mute them",
            listOf(
                FxParamDef("Hue", -180f, 180f, 0f, "°"),
                FxParamDef("Saturation", -100f, 100f, 0f),
                FxParamDef("Lightness", -100f, 100f, 0f),
                FxParamDef("Vibrance", -100f, 100f, 0f)
            )
        ),
        FxDef(
            LEVELS, 13, "Levels", "Color", "Black point, white point, gamma",
            listOf(
                FxParamDef("Input black", 0f, 255f, 0f),
                FxParamDef("Input white", 0f, 255f, 255f),
                FxParamDef("Gamma", 10f, 500f, 100f, "%", step = 5f),
                FxParamDef("Output black", 0f, 255f, 0f),
                FxParamDef("Output white", 0f, 255f, 255f)
            )
        ),
        FxDef(
            COLOR_BALANCE, 14, "Color Balance", "Color", "Tint shadows, midtones and highlights",
            listOf(
                FxParamDef("Shadow hue", 0f, 360f, 195f, "°", step = 3f),
                FxParamDef("Shadow amount", 0f, 100f, 35f, "%"),
                FxParamDef("Midtone hue", 0f, 360f, 33f, "°", step = 3f),
                FxParamDef("Midtone amount", 0f, 100f, 0f, "%"),
                FxParamDef("Highlight hue", 0f, 360f, 33f, "°", step = 3f),
                FxParamDef("Highlight amount", 0f, 100f, 35f, "%"),
                FxParamDef("Keep brightness", 0f, 100f, 100f, "%")
            )
        ),
        FxDef(
            TINT, 15, "Tint", "Color", "Two-colour look from shadows to highlights",
            listOf(
                FxParamDef("Shadow hue", 0f, 360f, 225f, "°", step = 3f),
                FxParamDef("Shadow strength", 0f, 100f, 60f, "%"),
                FxParamDef("Highlight hue", 0f, 360f, 36f, "°", step = 3f),
                FxParamDef("Highlight strength", 0f, 100f, 40f, "%"),
                FxParamDef("Amount", 0f, 100f, 80f, "%")
            )
        ),
        FxDef(
            POSTERIZE, 16, "Posterize", "Color", "Fewer colour steps",
            listOf(
                FxParamDef("Levels", 2f, 32f, 6f),
                FxParamDef("Mix", 0f, 100f, 100f, "%")
            )
        ),
        // ---- v140: distort ----
        FxDef(
            WAVE, 17, "Wave Warp", "Distort", "Travelling sine wave, animated",
            listOf(
                FxParamDef("Height", 0f, 100f, 30f),
                FxParamDef("Wavelength", 0f, 100f, 30f),
                FxParamDef("Direction", 0f, 360f, 90f, "°", step = 3f),
                FxParamDef("Phase", 0f, 360f, 0f, "°", step = 3f),
                FxParamDef("Speed", -100f, 100f, 30f)
            )
        ),
        FxDef(
            TWIRL, 18, "Twirl", "Distort", "Swirl the picture around a point",
            listOf(
                FxParamDef("Angle", -720f, 720f, 180f, "°", step = 5f),
                FxParamDef("Radius", 0f, 100f, 50f, "%"),
                FxParamDef("Centre X", 0f, 100f, 50f, "%"),
                FxParamDef("Centre Y", 0f, 100f, 50f, "%")
            )
        ),
        FxDef(
            BULGE, 19, "Bulge", "Distort", "Lens bulge or pinch",
            listOf(
                FxParamDef("Amount", -100f, 100f, 50f),
                FxParamDef("Radius", 0f, 100f, 50f, "%"),
                FxParamDef("Centre X", 0f, 100f, 50f, "%"),
                FxParamDef("Centre Y", 0f, 100f, 50f, "%")
            )
        ),
        FxDef(
            MIRROR, 20, "Mirror", "Distort", "Reflect one side onto the other",
            listOf(
                FxParamDef("Centre X", 0f, 100f, 50f, "%"),
                FxParamDef("Centre Y", 0f, 100f, 50f, "%"),
                FxParamDef("Angle", 0f, 360f, 90f, "°", step = 3f)
            )
        ),
        FxDef(
            KALEIDOSCOPE, 21, "Kaleidoscope", "Distort", "Mirrored segments around a centre",
            listOf(
                FxParamDef("Segments", 2f, 24f, 6f),
                FxParamDef("Rotation", -180f, 180f, 0f, "°"),
                FxParamDef("Centre X", 0f, 100f, 50f, "%"),
                FxParamDef("Centre Y", 0f, 100f, 50f, "%"),
                FxParamDef("Zoom", 20f, 300f, 100f, "%"),
                FxParamDef("Source angle", 0f, 360f, 0f, "°", step = 3f)
            )
        ),
        FxDef(
            TURBULENT, 22, "Turbulent Displace", "Distort", "Fractal noise pushes pixels, animated",
            listOf(
                FxParamDef("Amount", 0f, 100f, 30f),
                FxParamDef("Size", 1f, 100f, 40f),
                FxParamDef("Complexity", 1f, 6f, 3f),
                FxParamDef("Evolution", 0f, 360f, 0f, "°", step = 3f),
                FxParamDef("Speed", -100f, 100f, 25f)
            )
        ),
        // ---- v140: stylize ----
        FxDef(
            MOSAIC, 23, "Mosaic", "Stylize", "Big square pixels",
            listOf(
                FxParamDef("Block size", 1f, 100f, 20f),
                FxParamDef("Gap", 0f, 40f, 0f, "%")
            )
        ),
        FxDef(
            HALFTONE, 24, "Halftone", "Stylize", "Printed ink dots, mono or colour",
            listOf(
                FxParamDef("Dot size", 2f, 60f, 12f),
                FxParamDef("Angle", 0f, 360f, 45f, "°", step = 3f),
                FxParamDef("Softness", 0f, 100f, 20f, "%"),
                FxParamDef("Colour", 0f, 100f, 100f, "%"),
                FxParamDef("Mix", 0f, 100f, 100f, "%")
            )
        ),
        FxDef(
            SCANLINES, 25, "Scanlines", "Stylize", "CRT lines, RGB mask, flicker, roll bar",
            listOf(
                FxParamDef("Intensity", 0f, 100f, 45f, "%"),
                FxParamDef("Line size", 1f, 20f, 3f),
                FxParamDef("RGB mask", 0f, 100f, 25f, "%"),
                FxParamDef("Flicker", 0f, 100f, 20f, "%"),
                FxParamDef("Roll speed", -100f, 100f, 0f)
            )
        ),
        FxDef(
            GLITCH, 26, "Glitch", "Stylize", "Jumping bands, RGB split, line wobble",
            listOf(
                FxParamDef("Amount", 0f, 100f, 50f, "%"),
                FxParamDef("Slice size", 1f, 100f, 30f),
                FxParamDef("RGB split", 0f, 100f, 40f, "%"),
                FxParamDef("Rate", 1f, 24f, 12f, "/s"),
                FxParamDef("Jitter", 0f, 100f, 20f, "%")
            )
        ),
        // ---- v140: sharpen (in Texture) ----
        FxDef(
            SHARPEN, 9, "Sharpen", "Texture", "Unsharp mask on brightness, with halo control",
            listOf(
                FxParamDef("Amount", 0f, 300f, 100f, "%", step = 4f),
                FxParamDef("Radius", 0f, 100f, 20f),
                FxParamDef("Threshold", 0f, 100f, 0f),
                FxParamDef("Halo control", 0f, 100f, 40f, "%")
            )
        ),
        // ---- v149: 27 more effects ----
        FxDef(
            INVERT, 27, "Invert", "Color", "Flip every colour, or only the brightness",
            listOf(
                FxParamDef("Amount", 0f, 100f, 100f, "%"),
                FxParamDef("Luminance only", 0f, 100f, 0f, "%")
            )
        ),
        FxDef(
            BW, 28, "Black & White", "Color", "Mix the channels yourself, optional sepia / tone",
            listOf(
                FxParamDef("Reds", -50f, 200f, 30f, "%"),
                FxParamDef("Greens", -50f, 200f, 59f, "%"),
                FxParamDef("Blues", -50f, 200f, 11f, "%"),
                FxParamDef("Amount", 0f, 100f, 100f, "%"),
                FxParamDef("Tone hue", 0f, 360f, 36f, "°", step = 3f),
                FxParamDef("Tone amount", 0f, 100f, 0f, "%")
            )
        ),
        FxDef(
            THRESHOLD, 29, "Threshold", "Stylize", "Pure black and white around a level",
            listOf(
                FxParamDef("Level", 0f, 255f, 128f),
                FxParamDef("Softness", 0f, 100f, 5f, "%"),
                FxParamDef("Mix", 0f, 100f, 100f, "%")
            )
        ),
        FxDef(
            TEMPERATURE, 30, "Temperature & Tint", "Color", "Warmer or cooler, greener or magenta",
            listOf(
                FxParamDef("Temperature", -100f, 100f, 30f),
                FxParamDef("Tint", -100f, 100f, 0f)
            )
        ),
        FxDef(
            SHADOW_HIGHLIGHT, 31, "Shadows / Highlights", "Color", "Open up dark parts, calm bright parts",
            listOf(
                FxParamDef("Shadows", 0f, 100f, 50f, "%"),
                FxParamDef("Highlights", 0f, 100f, 20f, "%"),
                FxParamDef("Tonal width", 0f, 100f, 50f, "%"),
                FxParamDef("Midtone contrast", -100f, 100f, 0f)
            )
        ),
        FxDef(
            LEAVE_COLOR, 32, "Color Pop", "Color", "Keep one colour, everything else goes grey",
            listOf(
                FxParamDef("Hue", 0f, 360f, 0f, "°", step = 3f),
                FxParamDef("Range", 0f, 100f, 25f, "%"),
                FxParamDef("Softness", 0f, 100f, 30f, "%"),
                FxParamDef("Fade others", 0f, 100f, 100f, "%")
            )
        ),
        FxDef(
            CINEMATIC, 33, "Cinematic Grade", "Color", "Teal shadows, warm highlights, film contrast",
            listOf(
                FxParamDef("Amount", 0f, 100f, 60f, "%"),
                FxParamDef("Contrast", 0f, 100f, 30f, "%"),
                FxParamDef("Fade", 0f, 100f, 20f, "%"),
                FxParamDef("Saturation", -100f, 100f, -10f)
            )
        ),
        FxDef(
            SOLARIZE, 34, "Solarize", "Stylize", "Bright tones flip over",
            listOf(
                FxParamDef("Threshold", 0f, 100f, 50f, "%"),
                FxParamDef("Amount", 0f, 100f, 100f, "%")
            )
        ),
        FxDef(
            LENS_BLUR, 35, "Lens Blur", "Blur", "Bokeh discs, highlights bloom",
            listOf(
                FxParamDef("Radius", 0f, 100f, 30f),
                FxParamDef("Highlight boost", 0f, 100f, 40f, "%"),
                FxParamDef("Threshold", 0f, 100f, 70f, "%")
            )
        ),
        FxDef(
            TILT_SHIFT, 36, "Tilt-Shift", "Blur", "Sharp band, blurred above and below (miniature look)",
            listOf(
                FxParamDef("Blur", 0f, 100f, 40f),
                FxParamDef("Focus position", 0f, 100f, 50f, "%"),
                FxParamDef("Focus width", 0f, 100f, 20f, "%"),
                FxParamDef("Angle", 0f, 180f, 0f, "°", step = 3f),
                FxParamDef("Transition", 0f, 100f, 50f, "%")
            )
        ),
        FxDef(
            SURFACE_BLUR, 37, "Surface Blur", "Blur", "Smooths skin and flat areas, keeps the edges",
            listOf(
                FxParamDef("Radius", 0f, 100f, 30f),
                FxParamDef("Threshold", 0f, 100f, 30f)
            )
        ),
        FxDef(
            LIGHT_RAYS, 38, "Light Rays", "Light", "God rays from the bright parts",
            listOf(
                FxParamDef("Threshold", 0f, 100f, 70f, "%"),
                FxParamDef("Length", 0f, 100f, 60f, "%"),
                FxParamDef("Intensity", 0f, 300f, 100f, "%", step = 4f),
                FxParamDef("Centre X", 0f, 100f, 50f, "%"),
                FxParamDef("Centre Y", 0f, 100f, 30f, "%"),
                FxParamDef("Colour hue", 0f, 360f, 36f, "°", step = 3f),
                FxParamDef("Colour amount", 0f, 100f, 30f, "%")
            )
        ),
        FxDef(
            RIPPLE, 39, "Ripple", "Distort", "Rings spread out from a point, animated",
            listOf(
                FxParamDef("Amplitude", 0f, 100f, 30f),
                FxParamDef("Wavelength", 0f, 100f, 30f),
                FxParamDef("Centre X", 0f, 100f, 50f, "%"),
                FxParamDef("Centre Y", 0f, 100f, 50f, "%"),
                FxParamDef("Radius", 0f, 100f, 60f, "%"),
                FxParamDef("Speed", -100f, 100f, 40f),
                FxParamDef("Phase", 0f, 360f, 0f, "°", step = 3f)
            )
        ),
        FxDef(
            LENS_DISTORT, 40, "Lens Distortion", "Lens", "Barrel (+) or pincushion (-), colour fringes",
            listOf(
                FxParamDef("Curvature", -100f, 100f, 30f),
                FxParamDef("Zoom", 50f, 200f, 100f, "%"),
                FxParamDef("Chromatic aberration", 0f, 100f, 0f, "%"),
                FxParamDef("Centre X", 0f, 100f, 50f, "%"),
                FxParamDef("Centre Y", 0f, 100f, 50f, "%")
            )
        ),
        FxDef(
            POLAR, 41, "Polar Coordinates", "Distort", "Bend the picture into a circle and back",
            listOf(
                FxParamDef("Interpolation", 0f, 100f, 100f, "%"),
                FxParamDef("Reverse", 0f, 1f, 0f)
            )
        ),
        FxDef(
            TILE, 42, "Motion Tile", "Distort", "Repeat the picture, mirror it, scroll it",
            listOf(
                FxParamDef("Tiles X", 1f, 10f, 2f),
                FxParamDef("Tiles Y", 1f, 10f, 2f),
                FxParamDef("Mirror edges", 0f, 1f, 0f),
                FxParamDef("Phase X", -100f, 100f, 0f, "%"),
                FxParamDef("Phase Y", -100f, 100f, 0f, "%"),
                FxParamDef("Scroll X", -100f, 100f, 0f),
                FxParamDef("Scroll Y", -100f, 100f, 0f)
            )
        ),
        FxDef(
            SHAKE, 43, "Camera Shake", "Distort", "Handheld / earthquake shake, same in preview and export",
            listOf(
                FxParamDef("Amount", 0f, 100f, 30f),
                FxParamDef("Rate", 1f, 24f, 12f, "/s"),
                FxParamDef("Rotation", 0f, 100f, 20f),
                FxParamDef("Scale fill", 0f, 100f, 30f, "%"),
                FxParamDef("Smoothness", 0f, 100f, 50f, "%"),
                FxParamDef("Seed", 0f, 100f, 0f)
            )
        ),
        FxDef(
            EMBOSS, 44, "Emboss", "Stylize", "The picture as a lit relief",
            listOf(
                FxParamDef("Amount", 0f, 300f, 100f, "%", step = 4f),
                FxParamDef("Direction", 0f, 360f, 135f, "°", step = 3f),
                FxParamDef("Relief", 1f, 20f, 3f),
                FxParamDef("Keep colour", 0f, 100f, 0f, "%")
            )
        ),
        FxDef(
            FIND_EDGES, 45, "Find Edges", "Stylize", "Ink outlines or neon edges",
            listOf(
                FxParamDef("Strength", 0f, 300f, 100f, "%", step = 4f),
                FxParamDef("Thickness", 1f, 10f, 2f),
                FxParamDef("Glow style", 0f, 100f, 0f, "%"),
                FxParamDef("Colour", 0f, 100f, 0f, "%"),
                FxParamDef("Mix", 0f, 100f, 100f, "%")
            )
        ),
        FxDef(
            CARTOON, 46, "Cartoon", "Stylize", "Flat colours with ink outlines",
            listOf(
                FxParamDef("Colour levels", 2f, 16f, 6f),
                FxParamDef("Edge strength", 0f, 100f, 70f, "%"),
                FxParamDef("Edge thickness", 1f, 6f, 2f),
                FxParamDef("Smoothing", 0f, 100f, 40f, "%"),
                FxParamDef("Mix", 0f, 100f, 100f, "%")
            )
        ),
        FxDef(
            SKETCH, 47, "Pencil Sketch", "Stylize", "Hatching and outlines on paper",
            listOf(
                FxParamDef("Line density", 1f, 100f, 40f),
                FxParamDef("Darkness", 0f, 100f, 60f, "%"),
                FxParamDef("Angle", 0f, 360f, 45f, "°", step = 3f),
                FxParamDef("Edge strength", 0f, 100f, 60f, "%"),
                FxParamDef("Colour", 0f, 100f, 0f, "%"),
                FxParamDef("Mix", 0f, 100f, 100f, "%")
            )
        ),
        FxDef(
            OIL, 48, "Oil Paint", "Stylize", "Painted brush look (Kuwahara)",
            listOf(
                FxParamDef("Brush size", 0f, 100f, 30f),
                FxParamDef("Colour boost", 0f, 100f, 20f, "%")
            )
        ),
        FxDef(
            STROBE, 49, "Strobe Light", "Light", "Flashes on the beat",
            listOf(
                FxParamDef("Rate", 1f, 24f, 4f, "/s"),
                FxParamDef("Strength", 0f, 100f, 70f, "%"),
                FxParamDef("Flash length", 1f, 100f, 20f, "%"),
                FxParamDef("Invert", 0f, 100f, 0f, "%")
            )
        ),
        FxDef(
            VHS, 50, "VHS", "Texture", "Tape wobble, tracking band, smeared colour",
            listOf(
                FxParamDef("Amount", 0f, 100f, 60f, "%"),
                FxParamDef("Wobble", 0f, 100f, 40f),
                FxParamDef("Colour bleed", 0f, 100f, 50f),
                FxParamDef("Tracking bar", 0f, 100f, 40f),
                FxParamDef("Tape noise", 0f, 100f, 30f),
                FxParamDef("Colour fade", 0f, 100f, 30f)
            )
        ),
        FxDef(
            OLD_FILM, 51, "Old Film", "Texture", "Scratches, dust, flicker, gate weave, sepia",
            listOf(
                FxParamDef("Scratches", 0f, 100f, 40f),
                FxParamDef("Dust", 0f, 100f, 40f),
                FxParamDef("Flicker", 0f, 100f, 30f),
                FxParamDef("Gate weave", 0f, 100f, 25f),
                FxParamDef("Sepia", 0f, 100f, 40f),
                FxParamDef("Fade", 0f, 100f, 30f)
            )
        ),
        FxDef(
            LIGHT_LEAK, 52, "Light Leak", "Light", "Warm light blobs drift over the picture",
            listOf(
                FxParamDef("Intensity", 0f, 100f, 60f, "%"),
                FxParamDef("Hue", 0f, 360f, 24f, "°", step = 3f),
                FxParamDef("Size", 0f, 100f, 50f),
                FxParamDef("Speed", -100f, 100f, 20f),
                FxParamDef("Position", 0f, 100f, 30f)
            )
        ),
        FxDef(
            LENS_FLARE, 53, "Lens Flare", "Light", "Core, halo, streak and ghosts",
            listOf(
                FxParamDef("Centre X", 0f, 100f, 30f, "%"),
                FxParamDef("Centre Y", 0f, 100f, 30f, "%"),
                FxParamDef("Brightness", 0f, 200f, 100f, "%", step = 2f),
                FxParamDef("Size", 0f, 100f, 50f),
                FxParamDef("Ghosts", 0f, 100f, 60f, "%"),
                FxParamDef("Streak", 0f, 100f, 50f, "%"),
                FxParamDef("Hue", 0f, 360f, 36f, "°", step = 3f)
            )
        ),
        FxDef(
            COPY_BG, 54, "Copy Background", "Backdrop",
            "Text / shape / sticker: shows what is behind the layer inside its shape. Put it FIRST, then Blur etc. for frosted glass",
            listOf(FxParamDef("Amount", 0f, 100f, 100f, "%"))
        )
    )

    fun def(type: String): FxDef? = all.firstOrNull { it.id == type }

    fun create(type: String): ClipFx? {
        val d = def(type) ?: return null
        return ClipFx(type = type, values = d.params.map { it.default } + extraValues(type))
    }

    /**
     * Values past the declared params ([ClipFx.values] from index 7 on): Glow Scan's glow colour,
     * and the EXT groups of the v163 tier (Four Color Gradient's anchor points, Turbulent
     * Displace's and Pixelate's secondary params — Alight's own defaults). Panel edits keep them
     * (the Long Shadow colour precedent); [toNative] falls back to them when a value is missing.
     */
    fun extraValues(type: String): List<Float> = when (type) {
        GLOW_SCAN -> listOf(0x0088FF.toFloat())
        FOUR_COLOR_GRADIENT -> listOf(
            packPoint(0f, -500f), packPoint(500f, -500f), packPoint(500f, 0f), packPoint(0f, 0f),
            0f, 0f, 0f
        )
        TURBULENT_DISPLACE_AM, PIXELATE_AM -> List(7) { 0f }
        COLOR_BALANCE_AM -> listOf(0f, 0f, 1f, 0f, 0f, 0f, 0f)
        HEX_SHIFT_AM -> listOf(0f, packPoint(0f, 0f), 0f, 0f, 0f, 0f, 0f)
        SOFT_GLOW_AM -> listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f)
        FILL_BEHIND_AM -> listOf(0f, 100f, 0f, 0f, 0f, 100f, 0f)
        WAVE_WARP_AM -> listOf(0.5f, 0f, 0f, 0f, 0f, 0f, 0f)
        FRACTAL_WARP_AM -> listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f)
        TURBULENT_DISPLACE3_AM -> listOf(0f, 0f, 0f, 0f, 1f, 1f, 2f)
        STREAKS_AM -> listOf(1f, 0f, 0f, 0f, 0f, 0f, 0f)
        SIMPLE_STARS_AM -> listOf(0f, 0f, 0f, 1f, 0f, 0f, 0f)
        DOTS_AM -> listOf(0f, 0f, 1f, 1f, 0f, 0f, 1f)
        RIDGES_AM -> listOf(0f, 0f, 0f, 0f, 1f, 1f, 2f)
        DISPLACE_MAP_RAD_AM -> listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f)
        SMOOTH_BEVEL_AM -> listOf(35f, 0xFFFFFF.toFloat(), 0xFFFFFF.toFloat(), 0f, 0f, 0f, 0f)
        // The extrude tier's EXT + EXT2 defaults are Alight's own def defaults (rotate 0,
        // position (0, 100, 500), the def's colours and light rig; the packs are decoded
        // in fx_plan.cpp / renderer_effects.cpp).
        EXTRUDE_AM -> listOf(
            0f, 0f, 0f, 0f, 100f, 500f, 0x3366AA.toFloat(),
            0xAA3366.toFloat(), 0xFFFFFF.toFloat(), packPoint(75f, -75f), 2660f, 10250f, 30f, 410000f
        )
        EXTRUDE_FLAT_AM -> listOf(
            0f, 0f, 0f, 0f, 100f, 500f, 0x3366AA.toFloat(),
            0xAA3366.toFloat(), 300f, 0f, 0f, 0f, 0f, 0f
        )
        // Edge Glow's EXT defaults are Alight's own def defaults: gamma 1, brightness 0,
        // tint color black, tint 0.
        EDGE_GLOW_AM -> listOf(1f, 0f, 0f, 0f, 0f, 0f, 0f)
        // Tier 4 EXT / EXT2 defaults are Alight's own def defaults.
        SPECTRALMAP_AM -> listOf(0xFF0000.toFloat(), 0x00FF00.toFloat(), 0x0000FF.toFloat(), 0f, 0f, 0f, 0f)
        TURBULENCE2_AM -> listOf(
            0f, 0f, 0f, 0f, 1f, 1f, 0f,
            1f, 6f, 0f, 0f, 0f, 0f, 0f
        )
        CHECKER2_AM -> listOf(1f, 1f, 0f, 0f, 0f, 0f, 0f)
        ELECTRIC_EDGES_AM -> listOf(
            0.5f, 0f, 0f, 1f, 0f, 0f, 1f,
            0f, 0xEEDD55.toFloat(), 0f, 20f, 0f, 0f, 0.42f
        )
        else -> emptyList()
    }

    /** The native type of an effect's EXT group (its values [7..13]), or null when it has none. */
    fun extNativeType(type: String): Int? = when (type) {
        FOUR_COLOR_GRADIENT -> 66
        TURBULENT_DISPLACE_AM -> 68
        PIXELATE_AM -> 73
        COLOR_BALANCE_AM -> 77
        HEX_SHIFT_AM -> 85
        SOFT_GLOW_AM -> 87
        FILL_BEHIND_AM -> 89
        WAVE_WARP_AM -> 91
        FRACTAL_WARP_AM -> 96
        TURBULENT_DISPLACE3_AM -> 99
        STREAKS_AM -> 102
        SIMPLE_STARS_AM -> 109
        DOTS_AM -> 111
        RIDGES_AM -> 113
        DISPLACE_MAP_RAD_AM -> 117
        SMOOTH_BEVEL_AM -> 119
        EXTRUDE_AM -> 124
        EXTRUDE_FLAT_AM -> 127
        EDGE_GLOW_AM -> 130
        SPECTRALMAP_AM -> 133
        TURBULENCE2_AM -> 137
        CHECKER2_AM -> 140
        ELECTRIC_EDGES_AM -> 147
        else -> null
    }

    /** The native type of an effect's EXT2 group (its values [14..20]), or null when it has none. */
    fun ext2NativeType(type: String): Int? = when (type) {
        EXTRUDE_AM -> 125
        EXTRUDE_FLAT_AM -> 128
        TURBULENCE2_AM -> 138
        ELECTRIC_EDGES_AM -> 148
        else -> null
    }

    /**
     * An Alight point (x, y) packed into one float: two 12-bit signed fields,
     * (x + 2048) * 4096 + (y + 2048) — exact in a float for |x|, |y| <= 2047 (the components are
     * rounded to whole units). fxParseOps' owner folds the group into q[], and the renderer
     * unpacks it (fxFourColorGradient in renderer_effects.cpp).
     */
    fun packPoint(x: Float, y: Float): Float {
        val ix = x.roundToInt().coerceIn(-2048, 2047) + 2048
        val iy = y.roundToInt().coerceIn(-2048, 2047) + 2048
        return (ix * 4096 + iy).toFloat()
    }

    /**
     * The enabled effects as the flat array the C++ engine reads (groups of 8 floats: type, p0..p6), or null when there
     * is nothing to run. Max [MAX_EFFECTS] effects. Motion Blur is NOT emitted here: it needs the layer's motion at
     * the frame time, so [com.example.engine.MotionBlurEval.resolve] rewrites it to directional / radial blurs first
     * (use that at every per-frame call site); the Repeat family is NOT emitted either: the draw
     * loops draw the layer once per copy ([com.example.engine.RepeatEval.copiesOf] for overlay
     * layers, per-copy placements for picture / group layers). A layer with only Motion Blur or only a Repeat still counts
     * in [hasEffects].
     */
    fun toNative(effects: List<ClipFx>): FloatArray? {
        val on = effects.filter {
            it.enabled && def(it.type) != null && it.type != MOTION_BLUR && it.type != REPEAT &&
                it.type != REPEAT_RADIAL && it.type != REPEAT_SCATTER
        }.take(MAX_EFFECTS)
        if (on.isEmpty()) return null
        val out = ArrayList<Float>(on.size * 8)
        for (fx in on) {
            val d = def(fx.type)!!
            out += d.nativeType.toFloat()
            for (k in 0 until 7) out += fx.values.getOrNull(k) ?: d.params.getOrNull(k)?.default ?: 0f
            // An extended effect's second group rides right behind its main one; the engine's
            // fxParseOps folds it into the same op (it never counts as an effect of its own).
            val ext = extNativeType(fx.type)
            if (ext != null) {
                val extra = extraValues(fx.type)
                out += ext.toFloat()
                for (k in 0 until 7) out += fx.values.getOrNull(7 + k) ?: extra.getOrNull(k) ?: 0f
            }
            // A third group (EXT2, the extrude tier) rides behind the EXT group; fxParseOps
            // folds it into the same op's r[] the same way.
            val ext2 = ext2NativeType(fx.type)
            if (ext2 != null) {
                val extra = extraValues(fx.type)
                out += ext2.toFloat()
                for (k in 0 until 7) out += fx.values.getOrNull(14 + k) ?: extra.getOrNull(7 + k) ?: 0f
            }
        }
        return out.toFloatArray()
    }

    /** True when the layer has any enabled effect, Motion Blur and Repeat included (they are resolved elsewhere, so [toNative] skips them). */
    fun hasEffects(effects: List<ClipFx>): Boolean = effects.any { it.enabled && def(it.type) != null }

    /**
     * Eyedropper for Chroma Key: [key hue 0..360, key strength 0..100, key brightness 0..100, brightness match 0..100] that make the key colour equal the picked colour (ANY colour)
     * ([argb] 0xAARRGGBB). Same maths as fxKeyFromRgb in cpp/engine/fx_plan.cpp (host-tested there).
     */
    fun keyFromRgb(argb: Long): FloatArray {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val mx = maxOf(r, g, b)
        val mn = minOf(r, g, b)
        val d = mx - mn
        var h = 0f
        if (d > 1e-6f) {
            h = when (mx) {
                r -> (((g - b) / d) % 6f)
                g -> (b - r) / d + 2f
                else -> (r - g) / d + 4f
            } * 60f
            if (h < 0f) h += 360f
        }
        val strength = minOf(100f, 100f * d / maxOf(0.2f, mx))
        val match = 100f * ((20f - strength) / 12f).coerceIn(0f, 1f)
        return floatArrayOf(h, strength, 100f * mx, match)
    }

    /** A swatch colour (0xAARRGGBB) for the key hue / strength sliders of Chroma Key. */
    fun keySwatchArgb(hue: Float, strength: Float, brightness: Float = 100f): Long {
        val h = ((hue % 360f) + 360f) % 360f / 60f
        val s = (strength / 100f).coerceIn(0f, 1f)
        val i = h.toInt() % 6
        val f = h - h.toInt()
        val p = 1f - s
        val q = 1f - s * f
        val t = 1f - s * (1f - f)
        val (r, g, b) = when (i) {
            0 -> Triple(1f, t, p)
            1 -> Triple(q, 1f, p)
            2 -> Triple(p, 1f, t)
            3 -> Triple(p, q, 1f)
            4 -> Triple(t, p, 1f)
            else -> Triple(1f, p, q)
        }
        val k = 0.25f + 0.75f * (brightness / 100f).coerceIn(0f, 1f)
        fun c(x: Float) = (x * k * 255f + 0.5f).toInt().coerceIn(0, 255).toLong()
        return 0xFF000000L or (c(r) shl 16) or (c(g) shl 8) or c(b)
    }

    /** Film grain pattern number of a timeline time: it changes 24 times a second, the same in the preview and the export. */
    fun grainFrame(timeMs: Long): Int = (timeMs * 24L / 1000L).toInt()
}
