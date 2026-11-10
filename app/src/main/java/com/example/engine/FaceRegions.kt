package com.example.engine

import kotlin.math.hypot

/**
 * Which parts of the face can be blurred, and the geometry that turns MediaPipe Face Landmarker
 * points (478 per face, normalised 0..1) into the shapes drawn into the mask. Pure Kotlin so it can
 * be unit-tested without a phone.
 */
enum class FaceRegion(val label: String) {
    FACE("Full face"),
    EYES("Eyes"),
    NOSE("Nose"),
    LIPS("Lips");

    companion object {
        fun fromName(name: String?): FaceRegion = values().firstOrNull { it.name == name } ?: FACE
    }
}

/** A point in mask pixels. */
data class Pt(val x: Float, val y: Float)

/** One closed shape of a region: convex hull of [indices], grown by [scale] around its centre and [stroke] (fraction of the face width). */
class FaceShape(val indices: IntArray, val scale: Float, val stroke: Float)

object FaceRegions {

    // Landmark numbers of the Face Landmarker mesh (same numbering as MediaPipe Face Mesh).
    private val FACE_OVAL = intArrayOf(
        10, 338, 297, 332, 284, 251, 389, 356, 454, 323, 361, 288, 397, 365, 379, 378, 400, 377, 152,
        148, 176, 149, 150, 136, 172, 58, 132, 93, 234, 127, 162, 21, 54, 103, 67, 109
    )
    private val LEFT_EYE = intArrayOf(
        263, 249, 390, 373, 374, 380, 381, 382, 362, 466, 388, 387, 386, 385, 384, 398
    )
    private val RIGHT_EYE = intArrayOf(
        33, 7, 163, 144, 145, 153, 154, 155, 133, 246, 161, 160, 159, 158, 157, 173
    )
    private val LIPS = intArrayOf(
        61, 146, 91, 181, 84, 17, 314, 405, 321, 375, 291, 185, 40, 39, 37, 0, 267, 269, 270, 409,
        78, 95, 88, 178, 87, 14, 317, 402, 318, 324, 308, 191, 80, 81, 82, 13, 312, 311, 310, 415
    )
    private val NOSE = intArrayOf(
        6, 197, 195, 5, 4, 1, 19, 94, 2, 98, 97, 326, 327, 129, 358, 48, 278, 64, 294, 219, 439,
        49, 279, 131, 360, 209, 429
    )

    /** Landmark numbers that give the width of the face (cheek to cheek). */
    const val CHEEK_LEFT = 234
    const val CHEEK_RIGHT = 454

    fun shapes(region: FaceRegion): List<FaceShape> = when (region) {
        FaceRegion.FACE -> listOf(FaceShape(FACE_OVAL, 1.08f, 0f))
        FaceRegion.EYES -> listOf(
            FaceShape(LEFT_EYE, 1.35f, 0.03f),
            FaceShape(RIGHT_EYE, 1.35f, 0.03f)
        )
        FaceRegion.NOSE -> listOf(FaceShape(NOSE, 1.25f, 0.02f))
        FaceRegion.LIPS -> listOf(FaceShape(LIPS, 1.3f, 0.02f))
    }

    /** Highest landmark number any region uses (a face with fewer points is ignored). */
    val maxIndex: Int = (FACE_OVAL + LEFT_EYE + RIGHT_EYE + LIPS + NOSE).max()

    /** Convex hull (Andrew's monotone chain), counter-clockwise on screen. Fewer than 3 points -> the points. */
    fun convexHull(points: List<Pt>): List<Pt> {
        if (points.size < 3) return points
        val p = points.sortedWith(compareBy({ it.x }, { it.y }))
        fun cross(o: Pt, a: Pt, b: Pt) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
        val lower = ArrayList<Pt>()
        for (pt in p) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], pt) <= 0f) lower.removeAt(lower.size - 1)
            lower.add(pt)
        }
        val upper = ArrayList<Pt>()
        for (pt in p.asReversed()) {
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], pt) <= 0f) upper.removeAt(upper.size - 1)
            upper.add(pt)
        }
        lower.removeAt(lower.size - 1)
        upper.removeAt(upper.size - 1)
        return lower + upper
    }

    /** Grows [hull] around its centre by [scale]. */
    fun grow(hull: List<Pt>, scale: Float): List<Pt> {
        if (hull.isEmpty()) return hull
        val cx = hull.sumOf { it.x.toDouble() }.toFloat() / hull.size
        val cy = hull.sumOf { it.y.toDouble() }.toFloat() / hull.size
        return hull.map { Pt(cx + (it.x - cx) * scale, cy + (it.y - cy) * scale) }
    }

    /**
     * Shapes (already grown) of [region] for one face. [lm] gives the landmark [index] as (x, y) in
     * 0..1; the result is in mask pixels of a [w] x [h] mask. [strokePx] receives the extra outline
     * width of each shape (fraction of the cheek-to-cheek distance, in pixels).
     */
    fun polygons(
        region: FaceRegion,
        count: Int,
        w: Int,
        h: Int,
        lm: (Int) -> Pair<Float, Float>
    ): List<Pair<List<Pt>, Float>> {
        if (count <= maxIndex) return emptyList()
        val a = lm(CHEEK_LEFT)
        val b = lm(CHEEK_RIGHT)
        val faceW = hypot((a.first - b.first) * w, (a.second - b.second) * h)
        return shapes(region).map { s ->
            val pts = s.indices.map { i -> lm(i).let { Pt(it.first * w, it.second * h) } }
            grow(convexHull(pts), s.scale) to (s.stroke * faceW)
        }
    }
}
