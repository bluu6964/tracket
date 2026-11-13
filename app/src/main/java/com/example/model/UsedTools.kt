package com.example.model

/**
 * Which tools of the clip tool row have actually been USED on a layer (build 166 —
 * Sarowar's directive 2026-11-13 ~20:22, item 5): when the layer is selected, the tool
 * buttons of the used tools read WHITE / bright in the B/W chrome, the unused ones stay
 * grey, so what is applied to the layer is visible at a glance.
 *
 * The rule is state-based and conservative — a tool counts as used exactly when the
 * clip carries a non-default trace of it:
 *  - "effects":    at least one effect in [TimelineClip.effects].
 *  - "animations": an In or Out animation is set (animIn / animOut).
 *  - "color":      the fill is not the plain default — a gradient / image / video fill
 *                  (fillMode != FILL_SOLID), a removed fill (FILL_NONE), or a fill /
 *                  text colour other than the default white.
 *  - "transform":  any keyframes, or a non-default transform (moved, scaled, turned,
 *                  3D-turned or depth-moved from the layer's resting state).
 *  - "crop":       the crop window is smaller than the whole picture on either axis.
 *  - "blend":      a blend mode other than Normal, or an opacity other than 100 %.
 *  - "masks":      at least one mask in [TimelineClip.masks].
 *  - "volume":     a volume other than 100 %, or the layer is muted.
 *  - "cutout":     a finished cutout is stored on the layer (cutoutDir set).
 *  - "parent":     the layer has a parent link.
 * Every other tool (roto, track, remove, stabilize, captions, face tools, freeze,
 * rename, edit, group tools ...) is an action without a readable stored state on the
 * clip, so it never reports used. Pure and unit tested (UsedToolsTest).
 */
object UsedTools {

    /** The default fill / text colour of a fresh layer (white). */
    const val DEFAULT_FILL: Long = 0xFFFFFFFFL

    fun usedToolsOf(clip: TimelineClip): Set<String> {
        val out = HashSet<String>()
        if (clip.effects.isNotEmpty()) out += "effects"
        if (clip.animIn != null || clip.animOut != null) out += "animations"
        if (clip.fillMode != FILL_SOLID || clip.textColor != DEFAULT_FILL) out += "color"
        if (clip.keyframes.isNotEmpty() || clip.transformX != 0f || clip.transformY != 0f ||
            clip.rotationDeg != 0f || clip.scalePercent != 100f ||
            clip.rotation3dX != 0f || clip.rotation3dY != 0f || clip.transformZ != 0f
        ) out += "transform"
        if (clip.cropWidthPercent < 99.9f || clip.cropHeightPercent < 99.9f) out += "crop"
        if (clip.blendMode != "Normal" || clip.opacity != 100f) out += "blend"
        if (clip.masks.isNotEmpty()) out += "masks"
        if (clip.volume != 1f || clip.isMuted) out += "volume"
        if (clip.cutoutDir != null) out += "cutout"
        if (clip.parent != null) out += "parent"
        return out
    }
}
