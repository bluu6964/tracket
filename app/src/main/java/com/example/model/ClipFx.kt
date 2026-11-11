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

    /** Most effects on one layer (kFxMaxOps in cpp/engine/fx_plan.h). */
    const val MAX_EFFECTS = 12

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
        else -> emptyList()
    }

    /** The native type of an effect's EXT group (its values [7..13]), or null when it has none. */
    fun extNativeType(type: String): Int? = when (type) {
        FOUR_COLOR_GRADIENT -> 66
        TURBULENT_DISPLACE_AM -> 68
        PIXELATE_AM -> 73
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
