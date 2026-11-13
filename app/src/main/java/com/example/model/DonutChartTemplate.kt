package com.example.model

import kotlin.math.cos
import kotlin.math.sin

/**
 * The "Donut Chart" template (Templates programme, reference commission batch 2,
 * 2026-11-13): Sarowar's uploaded reference video (720x1280, 30 fps, 11.47 s) — on a
 * black background a donut chart builds itself segment by segment: a red 50%
 * segment sweeps the left half, then 20% / 15% / 10% / 5% segments follow around
 * the ring, each with its percentage label outside the ring and a legend row
 * (coloured dot + "50% : Opportunistic Scams" style lines) appearing underneath;
 * thin white arc accents draw themselves around the ring while it builds. (The
 * written batch-2 note described a different video for this slot; the template
 * follows the video that was actually uploaded — see ROADMAP.)
 *
 * Each segment is a `.pie` wedge layer — the parametric shape the Alight importer
 * builds (see [PieOutline]) — with a two-stop gradient fill; the donut's hole is
 * one black circle over the wedges (the classic punch-out: the wedges stay whole
 * discs underneath, so each segment's sweep is a single angle track). Segment 1
 * grows counterclockwise from the top (the video fills the left half first); the
 * others grow clockwise. Both directions are the same wrap rule read two ways —
 * verified against [PieOutline.sweepDegrees] in the harness:
 *  - a CCW-grown segment spanning authored [A, B] keys its startAngle A-360 -> B-360
 *    with endAngle fixed at A;
 *  - a CW-grown segment spanning authored [A, B] keys its startAngle B -> A with
 *    endAngle fixed at B-360.
 * (Authored angles grow counterclockwise on screen, 0 at +x, 90 at the top.)
 * The endpoint cases (delta 0 / delta 360) draw nothing in Alight's rule, so a
 * segment starts exactly empty and ends exactly full — no epsilon needed here.
 * Everything is ordinary layers: the wedges are PATH shapes whose angle tracks
 * are keyframes; labels and legend lines are plain text the user retypes (their
 * own percentages should be retyped with the words); the timings are theirs to
 * retime. No rig.
 *
 * DEVIATIONS from the video, honestly: the legend lines type in letter by letter
 * in the video — here they fade/slide in (the app's typewriter is a text-progress
 * animator the template could carry, but a fade keeps every line a plain editable
 * text layer; the motion difference is small on screen). The video's build-up
 * restarts around 10 s (its own loop); the template builds once over ~6.3 s and
 * holds the finished chart to 10 s. The "0x" label at the top is kept as an
 * editable text layer, as it appears in the video.
 */
object DonutChartSpec {
    const val DURATION_MS = 10000L

    const val BG = 0xFF050506L

    /** Segment shares (percent) and authored spans (degrees, CCW from 90 = top). */
    val PERCENTS = listOf(50, 20, 15, 10, 5)
    /** Authored (A, B) per segment: seg 1 sweeps CCW (left half); segs 2-5 run CW down the right side. */
    val SPANS = listOf(
        90f to 270f,
        18f to 90f,
        -36f to 18f,
        -72f to -36f,
        -90f to -72f
    )
    /** Gradient stops per segment (inner -> outer tone of the same hue). */
    val GRADIENTS = listOf(
        0xFF8F0E1EL to 0xFFE8112DL,
        0xFFB33A12L to 0xFFF4652AL,
        0xFFC25E0AL to 0xFFF59A23L,
        0xFFA8B51BL to 0xFFD7DF23L,
        0xFF12B76AL to 0xFF2EE66BL
    )
    val LEGEND = listOf(
        "50% : Opportunistic Scams",
        "20% : Overly Ambitious",
        "15% : \"Too early\" solutions",
        "10% : Good Investments",
        "5% : The Next \"Amazons\""
    )
    /** Each segment's build window [start, end] in ms. */
    val WINDOWS = listOf(
        300L to 2000L,
        2000L to 3300L,
        3300L to 4300L,
        4300L to 5300L,
        5300L to 6300L
    )

    /** Donut centre: (0, [CY_FRAC] of ph). Outer radius [R_FRAC] of pw; the hole [HOLE_FRAC]. */
    const val CY_FRAC = -0.20f
    const val R_FRAC = 0.315f
    const val HOLE_FRAC = 0.375f
    const val LABEL_R_FRAC = 0.40f
    const val LABEL_FONT_FRAC = 0.034f
    const val LEGEND_Y0_FRAC = 0.115f      // of ph, first legend row
    const val LEGEND_PITCH_FRAC = 0.036f   // of ph
    const val LEGEND_FONT_FRAC = 0.030f
    const val DOT_D_FRAC = 0.020f
    const val TITLE_Y_FRAC = -0.435f       // of ph ("0x")
    const val TITLE_FONT_FRAC = 0.030f

    /** Frame position of segment [index]'s % label (its span's mid-angle at [LABEL_R_FRAC]). */
    fun labelPos(index: Int, pw: Float, ph: Float): Pair<Float, Float> {
        val (a, b) = SPANS[index]
        val mid = Math.toRadians(((a + b) / 2f).toDouble())
        val r = LABEL_R_FRAC * pw
        return (r * cos(mid)).toFloat() to (CY_FRAC * ph - r * sin(mid)).toFloat()
    }

    /** Legend row [index]'s centre y (stored px from the canvas centre). */
    fun legendY(index: Int, ph: Float): Float = (LEGEND_Y0_FRAC + index * LEGEND_PITCH_FRAC) * ph

    val EASE_CUBIC_OUT = Easing(0.215f, 0.61f, 0.355f, 1f)
    val EASE_CUBIC_IN_OUT = Easing(0.645f, 0.045f, 0.355f, 1f)
    val EASE_BACK_OUT = Easing(0.175f, 0.885f, 0.32f, 1.2f)
}

object DonutChartTemplate {

    /** One donut segment's layers. */
    data class Segment(
        val wedge: TimelineClip,
        val label: TimelineClip,
        val dot: TimelineClip,
        val legendText: TimelineClip,
        val all: List<TimelineClip>
    )

    /** What [buildGroup] hands back: the Group layer, the named parts, and the lanes. */
    data class Insert(
        val group: TimelineClip,
        val background: TimelineClip,
        val title: TimelineClip,
        val hole: TimelineClip,
        val arcOuter: TimelineClip,
        val arcInner: TimelineClip,
        val segments: List<Segment>,
        /** Every layer inside the group in lane order (top lane first). */
        val children: List<TimelineClip>,
        /** Lanes to place on top of the project, top lane first: the group, then [children]. */
        val lanesTopFirst: List<TimelineClip>
    )

    /**
     * The template as ONE Group layer + its ordinary layers, ready to insert into
     * the project that is open. The group starts at [insertAtMs] and spans
     * [DonutChartSpec.DURATION_MS]; sizes ride [previewWidthPx] (the donut is
     * round on any canvas), vertical anchors ride [previewHeightPx].
     */
    fun buildGroup(
        previewWidthPx: Float,
        previewHeightPx: Float,
        insertAtMs: Long,
        idBase: String = "donut"
    ): Insert {
        val pw = if (previewWidthPx > 1f) previewWidthPx else 1080f
        val ph = if (previewHeightPx > 1f) previewHeightPx else 1920f
        val box = pw * 130f / 360f
        val dur = DonutChartSpec.DURATION_MS
        val cy = DonutChartSpec.CY_FRAC * ph
        val rPx = DonutChartSpec.R_FRAC * pw

        val groupId = "$idBase-group"
        val link = GroupOps.linkTo(groupId, GroupOps.Pose.REST)
        val group = TimelineClip(
            id = groupId,
            name = "Donut Chart",
            type = LayerType.GROUP,
            startMs = insertAtMs,
            durationMs = dur,
            color = 0xFFE53935L
        )
        fun base(id: String, name: String, type: LayerType, color: Long): TimelineClip = TimelineClip(
            id = id, name = name, type = type,
            startMs = insertAtMs, durationMs = dur, color = color,
            groupId = groupId, parent = link
        )

        val background = base("$idBase-bg", "Background", LayerType.SHAPE, 0xFF455A64L).copy(
            fillMode = FILL_SOLID,
            textColor = DonutChartSpec.BG,
            shapePattern = "RECT",
            shapeScaleX = pw / box,
            shapeScaleY = ph / box,
            scalePercent = pw / box * 100f
        )

        val title = base("$idBase-title", "Title", LayerType.TEXT, 0xFF3992CEL).copy(
            text = "0x",
            textColor = 0xFF9A9A9AL,
            fontWeight = 600,
            scalePercent = DonutChartSpec.TITLE_FONT_FRAC / 0.11f * 100f,
            transformY = DonutChartSpec.TITLE_Y_FRAC * ph,
            keyframes = mapOf(
                AnimProp.OPACITY to listOf(
                    Keyframe(0L, 0f, 0f, DonutChartSpec.EASE_CUBIC_OUT),
                    Keyframe(600L, 100f, 0f)
                )
            )
        )

        fun wedge(index: Int): TimelineClip {
            val (a, b) = DonutChartSpec.SPANS[index]
            val (t0, t1) = DonutChartSpec.WINDOWS[index]
            val (g1, g2) = DonutChartSpec.GRADIENTS[index]
            // CCW growth (segment 1): startAngle keys A-360 -> B-360, endAngle fixed A.
            // CW growth (segments 2-5): startAngle keys B -> A, endAngle fixed B-360.
            val startKeys: List<Keyframe>
            val endKeys: List<Keyframe>
            val storedStart: Float
            val storedEnd: Float
            if (index == 0) {
                startKeys = listOf(
                    Keyframe(t0, a - 360f, 0f, DonutChartSpec.EASE_CUBIC_IN_OUT),
                    Keyframe(t1, b - 360f, 0f)
                )
                endKeys = listOf(Keyframe(0L, a, 0f))
                storedStart = b - 360f
                storedEnd = a
            } else {
                startKeys = listOf(
                    Keyframe(t0, b, 0f, DonutChartSpec.EASE_CUBIC_IN_OUT),
                    Keyframe(t1, a, 0f)
                )
                endKeys = listOf(Keyframe(0L, b - 360f, 0f))
                storedStart = a
                storedEnd = b - 360f
            }
            return base("$idBase-seg${index + 1}", "Segment ${DonutChartSpec.PERCENTS[index]}%", LayerType.SHAPE, 0xFFEF5350L).copy(
                fillMode = FILL_GRADIENT,
                gradType = GRAD_LINEAR,
                gradColor1 = g1,
                gradColor2 = g2,
                gradX1 = 0.15f, gradY1 = 0.1f, gradX2 = 0.9f, gradY2 = 0.95f,
                shapePattern = "PATH",
                pathSegments = PieOutline.normalisedSegments(storedStart.toDouble(), storedEnd.toDouble()),
                pieStartKeys = startKeys,
                pieEndKeys = endKeys,
                pieRadius = 1f,
                shapeScaleX = 2f * rPx / box,
                shapeScaleY = 2f * rPx / box,
                scalePercent = 2f * rPx / box * 100f,
                transformY = cy
            )
        }

        val segments = (0 until 5).map { i ->
            val w = wedge(i)
            val (_, t1) = DonutChartSpec.WINDOWS[i]
            val lp = DonutChartSpec.labelPos(i, pw, ph)
            val label = base("$idBase-pct${i + 1}", "Label ${DonutChartSpec.PERCENTS[i]}%", LayerType.TEXT, 0xFF3992CEL).copy(
                text = "${DonutChartSpec.PERCENTS[i]}%",
                textColor = 0xFFFFFFFFL,
                fontWeight = 700,
                scalePercent = DonutChartSpec.LABEL_FONT_FRAC / 0.11f * 100f,
                transformX = lp.first,
                transformY = lp.second,
                opacity = 0f,
                keyframes = mapOf(
                    AnimProp.OPACITY to listOf(
                        Keyframe(t1 - 150L, 0f, 0f, DonutChartSpec.EASE_CUBIC_OUT),
                        Keyframe(t1 + 200L, 100f, 0f)
                    ),
                    AnimProp.SCALE to listOf(
                        Keyframe(t1 - 150L, DonutChartSpec.LABEL_FONT_FRAC / 0.11f * 100f * 0.5f, 0f, DonutChartSpec.EASE_BACK_OUT),
                        Keyframe(t1 + 200L, DonutChartSpec.LABEL_FONT_FRAC / 0.11f * 100f, 0f)
                    )
                )
            )
            val legendStart = DonutChartSpec.WINDOWS[i].first + 400L
            val ly = DonutChartSpec.legendY(i, ph)
            val dotD = DonutChartSpec.DOT_D_FRAC * pw
            val dot = base("$idBase-dot${i + 1}", "Legend Dot ${i + 1}", LayerType.SHAPE, 0xFFEF5350L).copy(
                fillMode = FILL_SOLID,
                textColor = DonutChartSpec.GRADIENTS[i].second,
                shapePattern = "CIRCLE",
                shapeScaleX = dotD / box,
                shapeScaleY = dotD / box,
                scalePercent = dotD / box * 100f,
                transformX = -0.235f * pw,
                transformY = ly,
                opacity = 0f,
                keyframes = mapOf(
                    AnimProp.OPACITY to listOf(
                        Keyframe(legendStart, 0f, 0f, DonutChartSpec.EASE_CUBIC_OUT),
                        Keyframe(legendStart + 400L, 100f, 0f)
                    )
                )
            )
            val legendText = base("$idBase-legend${i + 1}", "Legend ${i + 1}", LayerType.TEXT, 0xFF3992CEL).copy(
                text = DonutChartSpec.LEGEND[i],
                textColor = 0xFFFFFFFFL,
                fontWeight = 600,
                scalePercent = DonutChartSpec.LEGEND_FONT_FRAC / 0.11f * 100f,
                transformX = -0.01f * pw,
                transformY = ly,
                opacity = 0f,
                keyframes = mapOf(
                    AnimProp.OPACITY to listOf(
                        Keyframe(legendStart, 0f, 0f, DonutChartSpec.EASE_CUBIC_OUT),
                        Keyframe(legendStart + 400L, 100f, 0f)
                    ),
                    AnimProp.POSITION to listOf(
                        Keyframe(legendStart, -0.04f * pw, ly, DonutChartSpec.EASE_CUBIC_OUT),
                        Keyframe(legendStart + 400L, -0.01f * pw, ly)
                    )
                )
            )
            Segment(w, label, dot, legendText, listOf(label, legendText, dot, w))
        }

        val holeD = DonutChartSpec.HOLE_FRAC * pw
        val hole = base("$idBase-hole", "Donut Hole", LayerType.SHAPE, 0xFF455A64L).copy(
            fillMode = FILL_SOLID,
            textColor = DonutChartSpec.BG,
            shapePattern = "CIRCLE",
            shapeScaleX = holeD / box,
            shapeScaleY = holeD / box,
            scalePercent = holeD / box * 100f,
            transformY = cy
        )

        fun arc(id: String, name: String, dFrac: Float, rot: Float, trim: Pair<Float, Float>, t0: Long, t1: Long): TimelineClip {
            val d = dFrac * pw
            return base(id, name, LayerType.SHAPE, 0xFFB0BEC5L).copy(
                fillMode = FILL_NONE,
                shapePattern = "CIRCLE",
                shapeScaleX = d / box,
                shapeScaleY = d / box,
                scalePercent = d / box * 100f,
                strokeColor = 0xFFFFFFFFL,
                strokeWidth = 360f * 0.004f,
                transformY = cy,
                rotationDeg = rot,
                opacity = 70f,
                strokeTrimStart = trim.first,
                strokeTrimEnd = trim.second,
                keyframes = mapOf(
                    AnimProp.STROKE_TRIM to listOf(
                        Keyframe(t0, trim.first, trim.first, DonutChartSpec.EASE_CUBIC_IN_OUT),
                        Keyframe(t1, trim.first, trim.second)
                    )
                )
            )
        }
        val arcOuter = arc("$idBase-arcouter", "Arc Accent Outer", 0.345f * 2f, -30f, 0.02f to 0.30f, 800L, 3000L)
        val arcInner = arc("$idBase-arcinner", "Arc Accent Inner", 0.175f * 2f, 140f, 0.55f to 0.85f, 1500L, 4000L)

        // Lane order (top lane first): title, legend rows + labels + dots, arcs, hole,
        // wedges, background. (The hole must cover the wedges' centres; the legend and
        // labels sit clear of the ring and keep the top lanes.)
        val children = ArrayList<TimelineClip>()
        children += title
        for (s in segments) {
            children += s.label
            children += s.legendText
            children += s.dot
        }
        children += arcOuter
        children += arcInner
        children += hole
        for (s in segments) children += s.wedge
        children += background
        val lanesTopFirst = listOf(group) + children
        return Insert(group, background, title, hole, arcOuter, arcInner, segments, children, lanesTopFirst)
    }
}
