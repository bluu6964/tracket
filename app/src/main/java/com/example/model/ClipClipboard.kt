package com.example.model

import java.util.UUID

/**
 * The in-app clipboard's payload rules (build 176 — Sarowar's tools 1+2 from the
 * missing-tools audit): copy / paste of a layer's keyframes and of its effect stack.
 * The clipboard itself lives in the ViewModel; everything here is pure and testable.
 *
 * Keyframe paste timing follows Alight Motion: the copied set's earliest key (over
 * ALL props) lands exactly on the playhead, relative offsets preserved. For every
 * pasted prop, the keys of the target inside that prop's pasted span are replaced;
 * keys outside the span and props that were not copied stay untouched.
 */

/** Deep copy of a keyframe map: fresh lists and fresh [Keyframe] values, so editing the source layer afterwards can never alias the clipboard. */
fun Map<AnimProp, List<Keyframe>>.deepCopyKeyframes(): Map<AnimProp, List<Keyframe>> =
    mapValues { (_, list) -> list.map { it.copy() } }

/** The earliest key time over every prop of a copied keyframe set, or null when the set holds no keys. */
fun Map<AnimProp, List<Keyframe>>.earliestKeyMs(): Long? =
    values.asSequence().flatten().minOfOrNull { it.timeMs }

/**
 * This layer with [copied] keyframes pasted at [anchorRelMs] (ms from THIS layer's start).
 * The anchor is coerced into the layer, the copied set's earliest key lands on it, and each
 * pasted prop replaces this layer's keys of that prop inside the prop's pasted span
 * ([first pasted key, last pasted key] of that prop) — nothing outside a span moves.
 * Pasted keys keep their values, easings and tags; keys landing past the layer's end are
 * kept (they take effect if the layer is lengthened, like any key the app already stores).
 */
fun TimelineClip.withPastedKeyframes(
    copied: Map<AnimProp, List<Keyframe>>,
    anchorRelMs: Long
): TimelineClip {
    val earliest = copied.earliestKeyMs() ?: return this
    val anchor = anchorRelMs.coerceIn(0L, durationMs)
    val shift = anchor - earliest
    var out = this
    for ((prop, list) in copied) {
        if (list.isEmpty()) continue
        val pasted = list.map { it.copy(timeMs = it.timeMs + shift) }.sortedBy { it.timeMs }
        val spanStart = pasted.first().timeMs
        val spanEnd = pasted.last().timeMs
        val kept = keyframes[prop].orEmpty().filter { it.timeMs < spanStart || it.timeMs > spanEnd }
        out = out.copy(keyframes = out.keyframes + (prop to (kept + pasted).sortedBy { it.timeMs }))
    }
    return out
}

/** Deep copy of an effect stack for the clipboard: fresh [ClipFx] values and fresh value lists (the keyframed param tails ride inside [ClipFx.values]). Ids are kept — a paste re-ids. */
fun List<ClipFx>.copyEffects(): List<ClipFx> = map { it.copy(values = it.values.toList()) }

/** The same stack with a fresh id per effect, so a pasted stack never shares an effect id with its source layer. */
fun List<ClipFx>.withFreshEffectIds(): List<ClipFx> =
    map { it.copy(id = UUID.randomUUID().toString(), values = it.values.toList()) }

/**
 * A deep copy of a whole layer for the layer clipboard (build 199, A7). Every collection the
 * layer owns is copied element-wise — keyframes, masks (states, points, keys), path segments,
 * pie/arc key lists, text animators — so editing the clipboard copy can never reach back into
 * the source layer, and vice versa. Effect ids are KEPT (the clipboard law: the stored copy
 * keeps ids, a paste re-ids — see [forPastedLayer]). Array-holding attachments
 * ([TimelineClip.trackFollow], corner-pin / planar-remove trackers, mask motion tracks) are
 * shared by reference, exactly as the layer duplicate treats them: their arrays are never
 * mutated in place anywhere in the editor.
 */
fun TimelineClip.deepCopyLayer(): TimelineClip = copy(
    keyframes = keyframes.deepCopyKeyframes(),
    effects = effects.copyEffects(),
    masks = masks.map { m ->
        m.copy(
            state = m.state.copy(),
            points = m.points.map { it.copy() },
            keys = m.keys.map { k -> k.copy(state = k.state.copy()) }
        )
    },
    pathSegments = pathSegments.map { it.copy(pts = it.pts.toList()) },
    pieStartKeys = pieStartKeys.map { it.copy() },
    pieEndKeys = pieEndKeys.map { it.copy() },
    arcStartKeys = arcStartKeys.map { it.copy() },
    arcEndKeys = arcEndKeys.map { it.copy() },
    textAnimators = textAnimators.map { ta ->
        ta.copy(props = ta.props.mapValues { (_, v) -> v.copy(value = v.value.toList(), keys = v.keys.map { it.copy() }) })
    }
)

/**
 * The clipboard layer made paste-ready (build 199, A7): the stored copy is deep-copied AGAIN
 * (so one copied layer can be pasted any number of times without two pastes sharing mutable
 * state), given a fresh layer id and fresh effect ids, and placed at [newStartMs].
 */
fun TimelineClip.forPastedLayer(newId: String, newStartMs: Long): TimelineClip {
    val deep = deepCopyLayer()
    return deep.copy(id = newId, startMs = newStartMs, effects = deep.effects.withFreshEffectIds())
}
