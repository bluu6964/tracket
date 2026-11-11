package com.example.engine

import com.example.model.TimelineClip

/**
 * Alight Motion layer masks (`blending="mask"`, [TimelineClip.isMask]): a mask layer is never drawn — the
 * layer it sits on is cut to the mask's own (animated) shape. The importer resolves each mask to the layer
 * directly below it in its scene and stores the link ([TimelineClip.maskTargetId]); the renderers use this
 * pairing to give a masked layer its own picture and cut that picture with the mask layer's rendering
 * (alpha multiply). A masked GROUP layer is cut the same way, as its composed picture (the renderer's
 * group pass), in the scope the group itself sits in. Kept free of Android types so the importer tests
 * can pin the pairing rules.
 */
object LayerMaskLinks {

    /**
     * The mask layer for every masked layer of ONE scope (the clips of one scene level — a mask only ever
     * cuts a sibling of its own level): target clip id -> the mask clip that cuts it.
     */
    fun masksByTarget(scopeClips: List<TimelineClip>): Map<String, TimelineClip> {
        val out = LinkedHashMap<String, TimelineClip>()
        for (c in scopeClips) {
            val t = c.maskTargetId
            if (c.isMask && t != null) out[t] = c
        }
        return out
    }

    /** True when [clip] is cut by a mask layer of its scope. */
    fun isMasked(clip: TimelineClip, masksByTarget: Map<String, TimelineClip>): Boolean =
        masksByTarget.containsKey(clip.id)
}
