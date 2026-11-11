package com.example.engine

import com.example.model.TimelineClip

/**
 * Alight Motion layer masks (`blending="mask"`, [TimelineClip.isMask]): a mask layer is never drawn — the
 * layer it sits on is cut to the mask's own (animated) shape. The importer resolves each mask to the layer
 * directly below it in its scene and stores the link ([TimelineClip.maskTargetId]); the renderers use this
 * pairing to give a masked layer its own picture and cut that picture with the mask layer's rendering
 * (alpha multiply). A masked GROUP layer is cut the same way, as its composed picture (the renderer's
 * group pass), in the scope the group itself sits in; a masked VIDEO layer is cut the same way again, as
 * its placed picture (the renderer's group pass around the media draw). A stack of mask layers over one
 * layer links EVERY mask of the stack to that layer: the layer is cut by each mask in turn, so it keeps
 * only where every mask is (the intersection of their shapes). Kept free of Android types so the importer
 * tests can pin the pairing rules.
 */
object LayerMaskLinks {

    /**
     * The mask layers for every masked layer of ONE scope (the clips of one scene level — a mask only ever
     * cuts a sibling of its own level): target clip id -> the mask clips that cut it, in scope order
     * (the order the masks sit in the scene, lowest first). The renderers apply the cuts in this order.
     */
    fun masksByTarget(scopeClips: List<TimelineClip>): Map<String, List<TimelineClip>> {
        val out = LinkedHashMap<String, MutableList<TimelineClip>>()
        for (c in scopeClips) {
            val t = c.maskTargetId
            if (c.isMask && t != null) out.getOrPut(t) { ArrayList() } += c
        }
        return out
    }

    /** True when [clip] is cut by a mask layer of its scope. */
    fun isMasked(clip: TimelineClip, masksByTarget: Map<String, List<TimelineClip>>): Boolean =
        masksByTarget.containsKey(clip.id)
}
