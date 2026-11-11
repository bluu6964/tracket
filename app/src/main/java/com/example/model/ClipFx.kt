package com.example.model

import kotlinx.serialization.Serializable
import java.util.UUID

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
        return ClipFx(type = type, values = d.params.map { it.default })
    }

    /**
     * The enabled effects as the flat array the C++ engine reads (groups of 8 floats: type, p0..p6), or null when there
     * is nothing to run. Max [MAX_EFFECTS] effects. Motion Blur is NOT emitted here: it needs the layer's motion at
     * the frame time, so [com.example.engine.MotionBlurEval.resolve] rewrites it to directional / radial blurs first
     * (use that at every per-frame call site); a layer with only Motion Blur still counts in [hasEffects].
     */
    fun toNative(effects: List<ClipFx>): FloatArray? {
        val on = effects.filter { it.enabled && def(it.type) != null && it.type != MOTION_BLUR }.take(MAX_EFFECTS)
        if (on.isEmpty()) return null
        val out = FloatArray(on.size * 8)
        on.forEachIndexed { i, fx ->
            val d = def(fx.type)!!
            out[i * 8] = d.nativeType.toFloat()
            for (k in 0 until 7) out[i * 8 + 1 + k] = fx.values.getOrNull(k) ?: d.params.getOrNull(k)?.default ?: 0f
        }
        return out
    }

    /** True when the layer has any enabled effect, Motion Blur included (it needs a time to resolve, so [toNative] skips it). */
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
