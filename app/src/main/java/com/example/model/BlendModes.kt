package com.example.model

/** How a blend mode combines the layer with what lies beneath it. */
enum class BlendKind {
    /** Plain "source over". */
    NORMAL,

    /** Separable / non-separable colour blend (Multiply, Screen, Hue, ...). */
    COLOR,

    /** The layer cuts the layers beneath it (Alpha Matte, Luma, ...). */
    MATTE
}

/**
 * One blending mode. [shaderId] is the number the export shader switches on, so the preview
 * (Compose BlendMode, see ui/util/LayerBlend.kt) and the export (GLSL, see engine/BlendEffect.kt)
 * show the same look.
 */
data class BlendSpec(
    val name: String,
    val category: String,
    val shaderId: Int,
    val kind: BlendKind
) {
    val isNormal: Boolean get() = kind == BlendKind.NORMAL
}

object BlendModes {
    const val NORMAL = "Normal"

    val categories: List<String> =
        listOf("Matte", "Darken", "Brighten", "Contrast", "Difference", "Color")

    val all: List<BlendSpec> = listOf(
        // Matte
        BlendSpec("Normal", "Matte", 0, BlendKind.NORMAL),
        BlendSpec("Alpha Matte", "Matte", 1, BlendKind.MATTE),
        BlendSpec("Mask Invert", "Matte", 2, BlendKind.MATTE),
        BlendSpec("Luma", "Matte", 3, BlendKind.MATTE),
        BlendSpec("Luma Invert", "Matte", 4, BlendKind.MATTE),
        // Darken
        BlendSpec("Darken", "Darken", 10, BlendKind.COLOR),
        BlendSpec("Multiply", "Darken", 11, BlendKind.COLOR),
        BlendSpec("Color Burn", "Darken", 12, BlendKind.COLOR),
        // Brighten
        BlendSpec("Lighten", "Brighten", 20, BlendKind.COLOR),
        BlendSpec("Screen", "Brighten", 21, BlendKind.COLOR),
        BlendSpec("Color Dodge", "Brighten", 22, BlendKind.COLOR),
        BlendSpec("Add", "Brighten", 23, BlendKind.COLOR),
        // Contrast
        BlendSpec("Overlay", "Contrast", 30, BlendKind.COLOR),
        BlendSpec("Soft Light", "Contrast", 31, BlendKind.COLOR),
        BlendSpec("Hard Light", "Contrast", 32, BlendKind.COLOR),
        // Difference
        BlendSpec("Difference", "Difference", 40, BlendKind.COLOR),
        BlendSpec("Exclusion", "Difference", 41, BlendKind.COLOR),
        // Color
        BlendSpec("Hue", "Color", 50, BlendKind.COLOR),
        BlendSpec("Saturation", "Color", 51, BlendKind.COLOR),
        BlendSpec("Color", "Color", 52, BlendKind.COLOR),
        BlendSpec("Luminosity", "Color", 53, BlendKind.COLOR)
    )

    private val byName: Map<String, BlendSpec> = all.associateBy { it.name }

    /** Unknown names (old projects) fall back to Normal. */
    fun find(name: String?): BlendSpec = byName[name] ?: all.first()

    fun inCategory(category: String): List<BlendSpec> = all.filter { it.category == category }
}

/** True when the layer needs any blending work (opacity below 100 % or a blend mode). */
val TimelineClip.hasBlending: Boolean
    get() = opacity < 99.9f || !BlendModes.find(blendMode).isNormal

/** Opacity as 0..1. */
val TimelineClip.opacityFraction: Float
    get() = (opacity / 100f).coerceIn(0f, 1f)
