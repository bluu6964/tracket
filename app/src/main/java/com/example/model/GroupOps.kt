package com.example.model

import java.util.UUID

/**
 * Real grouping (Alight Motion style), pure functions without Android dependencies.
 *
 * A Group is ONE layer to the outside (Alight Motion style): in the timeline it is a single bar, and everything a layer
 * can do (move / scale / turn, opacity, blend mode, effects, masks, keyframes) works on the whole group. The renderer
 * draws the layers inside it into their own picture first and then puts that picture on the canvas like one layer.
 * "Edit Group" goes inside: only the layers of that group are shown in the timeline and on the canvas.
 *
 * Every layer inside has [TimelineClip.groupId] = the group's id and a [TimelineClip.parent] link to it, so the group's
 * move / scale / turn reaches the layers (the maths is the one of the Null parent, see ParentRig). The group's opacity,
 * blend mode, effects and masks are NOT handed down: they act on the finished picture of the group.
 *
 * This is NOT the same as a Chain ([LayerType.CHAIN]): a Chain only puts layers side by side in one timeline lane.
 */
object GroupOps {

    /** Position / scale % / rotation of a group at the moment of linking (what a [ParentLink] saves). */
    data class Pose(val x: Float, val y: Float, val scalePercent: Float, val rotationDeg: Float) {
        companion object {
            /** A group that was never moved: the pose of a brand new group. */
            val REST = Pose(0f, 0f, 100f, 0f)
        }
    }

    /** Result of [group]: the new lanes and the id of the group that holds the layers now. */
    data class Grouped(val tracks: List<TimelineTrack>, val groupId: String)

    /** Audio has no picture, a Camera is a view and a Chain is a lane container: none of them can sit in a group. */
    fun canGroup(c: TimelineClip): Boolean =
        c.type != LayerType.AUDIO && c.type != LayerType.CAMERA && c.type != LayerType.CHAIN


    /** Ids of every Group layer in [tracks]. */
    fun groupIdsOf(tracks: List<TimelineTrack>): Set<String> =
        tracks.flatMapTo(HashSet()) { lane -> lane.clips.filter { it.type == LayerType.GROUP }.map { it.id } }

    /**
     * The group [c] is drawn inside: its groupId, or null when it is not inside a group (also when its group is not in
     * the list, e.g. a full-screen editor that shows only some layers).
     */
    fun scopeOf(c: TimelineClip, groupIds: Set<String>): String? = c.groupId?.takeIf { it in groupIds }

    /**
     * The lanes of one level: only the layers that sit directly inside [scopeId] (null = the top level, layers that are
     * in no group; a Group layer counts as one layer of its own level). Lanes left empty are dropped, the order is kept.
     * This is what the timeline and the canvas show, and what the renderer draws as one picture (see NativePreviewEngine).
     */
    fun scopeTracks(tracks: List<TimelineTrack>, scopeId: String?): List<TimelineTrack> {
        val ids = groupIdsOf(tracks)
        val scope = scopeId?.takeIf { it in ids }
        if (ids.isEmpty() && scope == null) return tracks
        val out = ArrayList<TimelineTrack>(tracks.size)
        for (lane in tracks) {
            if (lane.clips.all { scopeOf(it, ids) == scope }) { out += lane; continue }
            val kept = lane.clips.filter { scopeOf(it, ids) == scope }
            if (kept.isNotEmpty()) out += lane.copy(clips = kept)
        }
        return out
    }

    /** [editingId] when it is a Group layer that exists, else null (the top level). */
    fun validScope(tracks: List<TimelineTrack>, editingId: String?): String? =
        editingId?.takeIf { it in groupIdsOf(tracks) }

    /** Index in [tracks] of the lane that holds [laneId] (-1 = none). Used to turn a position in a shown level into the real one. */
    fun realLaneIndex(tracks: List<TimelineTrack>, laneId: String): Int = tracks.indexOfFirst { it.id == laneId }

    fun linkTo(groupId: String, pose: Pose): ParentLink =
        ParentLink(groupId, pose.x, pose.y, pose.scalePercent, pose.rotationDeg)

    /** The layers directly inside the group [groupId] (not the layers of a group inside it). */
    fun childrenOf(tracks: List<TimelineTrack>, groupId: String): List<TimelineClip> =
        tracks.flatMap { it.clips }.filter { it.groupId == groupId }

    /** All layers inside the group, also the ones in groups inside it. */
    fun descendantsOf(tracks: List<TimelineTrack>, groupId: String): List<TimelineClip> {
        val all = tracks.flatMap { it.clips }
        val seen = HashSet<String>()
        val out = ArrayList<TimelineClip>()
        var frontier = listOf(groupId)
        var guard = 0
        while (frontier.isNotEmpty() && guard++ < 16) {
            val next = ArrayList<String>()
            for (c in all) {
                val g = c.groupId ?: continue
                if (g in frontier && seen.add(c.id)) {
                    out += c
                    if (c.type == LayerType.GROUP) next += c.id
                }
            }
            frontier = next
        }
        return out
    }

    /** True when [candidateId] is [ofId] itself or one of its parents (Null / group chain): linking would make a loop. */
    private fun isSelfOrAncestor(all: Map<String, TimelineClip>, candidateId: String, ofId: String): Boolean {
        var cur: String? = ofId
        var n = 0
        while (cur != null && n++ < 16) {
            if (cur == candidateId) return true
            cur = all[cur]?.parent?.id
        }
        return false
    }

    /**
     * Groups the checked layers.
     * - Normal case: ONE new Group layer (own lane, right above the topmost checked layer); the checked layers get the
     *   group as parent and keep their own lanes. A checked Group becomes a group inside the new one.
     * - If exactly one existing Group is among the checked layers (and other layers too), the other layers go INTO that
     *   group instead of making a new one (this is how a layer is added to a group later).
     * A layer that already had a Null parent loses it (the group is its parent now).
     * [poseOf] = the pose of an existing group now (null = unknown, the layer then follows from the rest pose).
     * Returns null when there is nothing to group.
     */
    fun group(tracks: List<TimelineTrack>, ids: Set<String>, poseOf: (String) -> Pose? = { null }): Grouped? {
        val all = tracks.flatMap { it.clips }
        val byId = all.associateBy { it.id }
        val sel = all.filter { it.id in ids && canGroup(it) }
        if (sel.isEmpty()) return null

        val groupsSelected = sel.filter { it.type == LayerType.GROUP }
        val others = sel.filter { it.type != LayerType.GROUP }
        if (groupsSelected.size == 1 && others.isNotEmpty()) {
            val target = groupsSelected[0]
            val link = linkTo(target.id, poseOf(target.id) ?: Pose.REST)
            val movers = others.filter { !isSelfOrAncestor(byId, it.id, target.id) }.map { it.id }.toSet()
            if (movers.isEmpty()) return null
            val out = tracks.map { lane ->
                lane.copy(clips = lane.clips.map { c ->
                    if (c.id in movers) c.copy(groupId = target.id, parent = link) else c
                })
            }
            return Grouped(fitGroups(out), target.id)
        }
        if (sel.size == 1 && sel[0].type == LayerType.GROUP) return null  // a lone group: nothing to do

        val start = sel.minOf { it.startMs }
        val end = sel.maxOf { it.endMs }
        // Grouping layers that are all inside one group (Edit Group): the new group sits inside that group too.
        val outerId = sel.map { it.groupId }.distinct().singleOrNull()?.takeIf { byId[it]?.type == LayerType.GROUP }
        val group = TimelineClip(
            id = UUID.randomUUID().toString(),
            name = "Group ${all.count { it.type == LayerType.GROUP } + 1}",
            type = LayerType.GROUP,
            startMs = start,
            durationMs = (end - start).coerceAtLeast(100L),
            color = 0xFF5BB85D,
            groupId = outerId,
            parent = outerId?.let { linkTo(it, poseOf(it) ?: Pose.REST) }
        )
        val link = linkTo(group.id, Pose.REST)
        val selIds = sel.map { it.id }.toSet()
        val topIdx = tracks.indexOfFirst { lane -> lane.clips.any { it.id in selIds } }.coerceAtLeast(0)
        val moved = tracks.map { lane ->
            lane.copy(clips = lane.clips.map { c ->
                if (c.id in selIds) c.copy(groupId = group.id, parent = link) else c
            })
        }
        val out = moved.toMutableList()
        out.add(topIdx, LaneLayout.newLane(listOf(group)))
        return Grouped(fitGroups(out), group.id)
    }

    /**
     * Takes the layers out of the group [groupId] and removes the group layer.
     * [bake] gets a layer of the group and returns it with the pose it shows now (so it does not jump); the default
     * keeps its own pose. Layers of a group that sits inside another group stay in that outer group
     * (linked to it with [poseOf] = the outer group's pose now).
     */
    fun ungroup(
        tracks: List<TimelineTrack>,
        groupId: String,
        bake: (TimelineClip) -> TimelineClip = { it },
        poseOf: (String) -> Pose? = { null }
    ): List<TimelineTrack> {
        val group = tracks.flatMap { it.clips }.firstOrNull { it.id == groupId && it.type == LayerType.GROUP } ?: return tracks
        val outer = group.groupId
        val outerLink = outer?.let { linkTo(it, poseOf(it) ?: Pose.REST) }
        val out = tracks.map { lane ->
            lane.copy(clips = lane.clips.mapNotNull { c ->
                when {
                    c.id == groupId -> null
                    c.groupId == groupId -> bake(c).copy(groupId = outer, parent = outerLink)
                    else -> c
                }
            })
        }
        return LaneLayout.normalize(out.filter { it.clips.isNotEmpty() })
    }

    /**
     * A copy of the group with copies of everything inside it (also groups inside it), in new lanes on top.
     * The copies are linked to the copied group. Returns the new lanes and the id of the new group.
     */
    fun duplicate(tracks: List<TimelineTrack>, groupId: String): Grouped? {
        val group = tracks.flatMap { it.clips }.firstOrNull { it.id == groupId && it.type == LayerType.GROUP } ?: return null
        val inside = descendantsOf(tracks, groupId).map { it.id }.toSet()
        val originals = tracks.flatMap { it.clips }.filter { it.id == groupId || it.id in inside }  // top lane first
        val newIds = originals.associate { it.id to UUID.randomUUID().toString() }
        val copies = originals.map { c ->
            val link = c.parent?.let { p -> newIds[p.id]?.let { p.copy(id = it) } ?: p }
            c.copy(
                id = newIds.getValue(c.id),
                name = if (c.id == groupId) "${c.name} (Copy)" else c.name,
                groupId = if (c.id == groupId) c.groupId else c.groupId?.let { newIds[it] ?: it },
                parent = link,
                chainId = null
            )
        }
        val newLanes = copies.map { LaneLayout.newLane(listOf(it)) }
        return Grouped(newLanes + tracks, newIds.getValue(group.id))
    }

    /**
     * Splits the group [groupId] at [playheadMs] into two groups: the first keeps the group's id and everything before the
     * playhead, the second (new id, copy of the group's own move / scale / turn, shifted in time) gets everything after it.
     * A layer inside that spans the playhead is cut in two like a normal Split; a layer that lies wholly after the playhead
     * just moves to the second group. The new layers sit in new lanes right above the lane they came from.
     * Returns null when it cannot be done: not a group, the playhead is within 500 ms of an edge, the group is empty, or it
     * holds another group (split that one first).
     */
    fun splitGroup(tracks: List<TimelineTrack>, groupId: String, playheadMs: Long): Grouped? {
        val all = tracks.flatMap { it.clips }
        val group = all.firstOrNull { it.id == groupId && it.type == LayerType.GROUP } ?: return null
        if (playheadMs <= group.startMs + 500L || playheadMs >= group.endMs - 500L) return null
        val kids = all.filter { it.groupId == groupId }
        if (kids.isEmpty() || kids.any { it.type == LayerType.GROUP }) return null

        val d1 = playheadMs - group.startMs
        val newGroupId = UUID.randomUUID().toString()
        val g1 = group.copy(durationMs = d1, fadeOutMs = 0L).withoutClipAnim(AnimSlot.OUT)
        val g2 = group.copy(
            id = newGroupId,
            name = "${group.name} (Split)",
            startMs = playheadMs,
            durationMs = group.endMs - playheadMs,
            fadeInMs = 0L
        ).withShiftedKeyframes(-d1).withoutClipAnim(AnimSlot.IN)

        val firstHalves = HashMap<String, TimelineClip>()
        val secondHalves = HashMap<String, TimelineClip>()
        val movedWhole = HashSet<String>()
        val splitMap = HashMap<String, String>()   // id of a cut layer -> id of its second half
        for (c in kids) {
            when {
                c.endMs <= playheadMs -> Unit                      // stays in the first group as it is
                c.startMs >= playheadMs -> movedWhole += c.id      // goes to the second group
                else -> {
                    val dd1 = playheadMs - c.startMs
                    firstHalves[c.id] = c.copy(durationMs = dd1, fadeOutMs = 0L).withoutClipAnim(AnimSlot.OUT)
                    val c2 = c.copy(
                        id = UUID.randomUUID().toString(),
                        name = "${c.name} (Split)",
                        startMs = playheadMs,
                        durationMs = c.endMs - playheadMs,
                        trimStartMs = c.trimStartMs + c.sourceOffsetMs(dd1),
                        fadeInMs = 0L,
                        chainId = null
                    ).withShiftedKeyframes(-dd1).withoutClipAnim(AnimSlot.IN)
                    secondHalves[c.id] = c2
                    splitMap[c.id] = c2.id
                }
            }
        }

        /** A layer that now belongs to the second group: follows it, and follows the second half of a cut parent. */
        fun relink(c: TimelineClip): TimelineClip {
            val p = c.parent
            val np = when {
                p == null -> null
                p.id == groupId -> p.copy(id = newGroupId)
                splitMap.containsKey(p.id) -> p.copy(id = splitMap.getValue(p.id))
                else -> p
            }
            return c.copy(groupId = newGroupId, parent = np)
        }

        val out = ArrayList<TimelineTrack>(tracks.size + secondHalves.size + 1)
        for (lane in tracks) {
            val above = ArrayList<TimelineClip>()
            var holdsGroup = false
            val clips = lane.clips.map { c ->
                when {
                    c.id == groupId -> { holdsGroup = true; g1 }
                    firstHalves.containsKey(c.id) -> { above += relink(secondHalves.getValue(c.id)); firstHalves.getValue(c.id) }
                    c.id in movedWhole -> relink(c)
                    else -> c
                }
            }
            if (holdsGroup) out += LaneLayout.newLane(listOf(g2))
            for (a in above) out += LaneLayout.newLane(listOf(a))
            out += lane.copy(clips = clips)
        }
        return Grouped(LaneLayout.normalize(out), newGroupId)
    }

    /**
     * Every Group layer covers exactly the layers inside it (first start .. last end), also when they move. The group's
     * own keyframes stay where they were in project time. A layer whose group is gone becomes a normal layer again.
     * A group without layers keeps its own length, and so does a group whose edge the person trimmed
     * ([TimelineClip.groupFreeLength]): the layers inside are then cut at its ends.
     */
    fun fitGroups(tracks: List<TimelineTrack>): List<TimelineTrack> {
        var cur = tracks
        var pass = 0
        while (pass++ < 4) {
            val all = cur.flatMap { it.clips }
            val groups = all.filter { it.type == LayerType.GROUP }.associateBy { it.id }
            var changed = false
            val out = cur.map { lane ->
                var laneChanged = false
                val clips = lane.clips.map { c ->
                    var r = c
                    // Stale link: the group of this layer does not exist (any more).
                    if (r.groupId != null && r.groupId !in groups) {
                        r = r.copy(groupId = null, parent = if (r.parent?.id == c.groupId) null else r.parent)
                    }
                    // A group the person trimmed keeps its own length: the layers inside are cut at its ends.
                    if (r.type == LayerType.GROUP && !r.groupFreeLength) {
                        val kids = all.filter { it.groupId == r.id }
                        if (kids.isNotEmpty()) {
                            val start = kids.minOf { it.startMs }
                            val end = kids.maxOf { it.endMs }
                            if (r.startMs != start || r.endMs != end) {
                                val oldStart = r.startMs
                                val oldEnd = r.endMs
                                r = r.copy(startMs = start, durationMs = (end - start).coerceAtLeast(100L))
                                    .withTrimmedStartKeyframes(start - oldStart)
                                    .withEndMoved(end - oldEnd)
                            }
                        }
                    }
                    if (r !== c) laneChanged = true
                    r
                }
                if (laneChanged) { changed = true; lane.copy(clips = clips) } else lane
            }
            if (!changed) return cur
            cur = out
        }
        return cur
    }
}
