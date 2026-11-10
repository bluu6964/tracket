package com.example.engine

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Grey picture, values 0..255, row-major. Pure data: no Android classes, so it is unit-testable. */
class GrayImage(val w: Int, val h: Int, val px: FloatArray) {
    init {
        require(w > 0 && h > 0 && px.size == w * h) { "bad grey picture size" }
    }
}

/** One level of the image pyramid: the picture and its two gradient pictures. */
internal class LkLevel(val w: Int, val h: Int, val img: FloatArray, val ix: FloatArray, val iy: FloatArray)

/**
 * Follows a round region of a video (the circle the user puts on an object).
 *
 * How it works (no library needed):
 *  1. Corners with the strongest texture inside the circle are picked (Shi-Tomasi).
 *  2. Every new frame, each corner is followed by pyramidal Lucas-Kanade optical flow.
 *  3. A forward-backward check throws away corners that were not followed reliably.
 *  4. Deterministic RANSAC fits translation, rotation and uniform scale, rejecting background/outlier features.
 *  5. When too few corners are left, new ones are picked inside the transformed circle.
 *
 * All coordinates are in picture pixels. Call [start] with the first frame, then [step] with every
 * following frame; [cx] / [cy] are the circle centre after the latest call.
 */
class CircleTracker(private val maxPoints: Int = 60) {

    var cx = 0f
        private set
    var cy = 0f
        private set
    var radius = 0f
        private set

    /** Turn of the tracked object since [start], in degrees (clockwise in picture pixels, y down). */
    var rotationDeg = 0f
        private set

    /** Zoom of the tracked object since [start] (1 = first frame). */
    var scaleTotal = 1f
        private set

    /** Number of corners followed right now. */
    val pointCount: Int get() = n

    private var n = 0
    private val pts = FloatArray(maxPoints * 2)
    private var prev: Array<LkLevel>? = null
    private var startCount = 0
    private var sinceReseed = 0
    private var misses = 0

    /**
     * One frame could not be followed (motion blur, glare, a flash). The circle holds its place and
     * the next frame is followed from the same old picture, so a single bad frame does not end the
     * track; after [MAX_MISSES] bad frames in a row the object counts as lost.
     */
    private fun miss(): Boolean = ++misses <= MAX_MISSES

    // scratch buffers (one tracked point at a time)
    private val tpl = FloatArray(WIN * WIN)
    private val tgx = FloatArray(WIN * WIN)
    private val tgy = FloatArray(WIN * WIN)
    private val res = FloatArray(2)
    private val fwd = FloatArray(2)

    /** The corners followed right now, as x, y, x, y ... (picture pixels). For the live display. */
    fun copyPoints(): FloatArray = pts.copyOf(2 * n)

    /**
     * Puts the circle on the first frame and picks the corners. Returns false when the circle holds
     * too little texture to follow (plain wall, sky...): the caller should tell the user.
     */
    fun start(frame: GrayImage, cx: Float, cy: Float, radius: Float): Boolean {
        this.cx = cx
        this.cy = cy
        this.radius = radius.coerceAtLeast(4f)
        rotationDeg = 0f
        scaleTotal = 1f
        n = 0
        sinceReseed = 0
        misses = 0
        val pyr = buildPyramid(frame)
        prev = pyr
        detect(pyr[0])
        startCount = n
        return n >= MIN_POINTS
    }

    /**
     * Follows the circle into [frame] (same size as the first one). Returns false when the object
     * is lost (too few corners left, or the circle left the picture); the centre is then not moved.
     */
    fun step(frame: GrayImage): Boolean {
        val a = prev ?: return false
        val b = buildPyramid(frame)
        val w = frame.w
        val h = frame.h

        // 1) follow every corner forward, then back again (forward-backward check)
        val dxs = FloatArray(n)
        val dys = FloatArray(n)
        val ok = BooleanArray(n)
        var good = 0
        val maxJump = 0.4f * max(w, h)
        for (i in 0 until n) {
            val x = pts[2 * i]
            val y = pts[2 * i + 1]
            if (!trackPoint(a, b, x, y, fwd)) continue
            val fx = fwd[0]
            val fy = fwd[1]
            if (!trackPoint(b, a, fx, fy, res)) continue
            val ex = res[0] - x
            val ey = res[1] - y
            if (ex * ex + ey * ey > FB_TOL * FB_TOL) continue
            val dx = fx - x
            val dy = fy - y
            if (abs(dx) > maxJump || abs(dy) > maxJump) continue
            dxs[i] = dx
            dys[i] = dy
            ok[i] = true
            good++
        }
        if (good < MIN_POINTS) return miss()

        // 2) Robust similarity-motion fit. Translation-only median motion drifts when the
        // selected region rotates or scales, and can be pulled toward the background. A small
        // deterministic RANSAC searches for the largest agreeing set of feature motions, then
        // refines a translation + rotation + uniform-scale model from those inliers.
        val motion = estimateSimilarity(dxs, dys, ok, good)
        if (motion == null || motion.inliers < MIN_POINTS) return miss()
        val aMotion = motion.a
        val bMotion = motion.b
        val tx = motion.tx
        val ty = motion.ty
        val modelScale = sqrt(aMotion * aMotion + bMotion * bMotion)
        if (!modelScale.isFinite() || modelScale < MIN_SCALE || modelScale > MAX_SCALE) return miss()

        var kept = 0
        val newPts = FloatArray(maxPoints * 2)
        val inlierTol = motion.tolerance
        for (i in 0 until n) {
            if (!ok[i]) continue
            val x = pts[2 * i]
            val y = pts[2 * i + 1]
            val qx = aMotion * x - bMotion * y + tx
            val qy = bMotion * x + aMotion * y + ty
            val ex = x + dxs[i] - qx
            val ey = y + dys[i] - qy
            if (ex * ex + ey * ey > inlierTol * inlierTol) continue
            newPts[2 * kept] = x + dxs[i]
            newPts[2 * kept + 1] = y + dys[i]
            kept++
        }
        if (kept < MIN_POINTS) return miss()
        misses = 0

        val ncx = aMotion * cx - bMotion * cy + tx
        val ncy = bMotion * cx + aMotion * cy + ty
        if (ncx < -radius || ncy < -radius || ncx > w + radius || ncy > h + radius) return false

        // 3) commit: transform centre/radius and keep only corners still inside the tracked region.
        cx = ncx
        cy = ncy
        rotationDeg += Math.toDegrees(atan2(bMotion, aMotion).toDouble()).toFloat()
        scaleTotal *= modelScale
        radius = (radius * modelScale).coerceIn(4f, max(w, h).toFloat())
        var m = 0
        val lim = (radius * 1.25f) * (radius * 1.25f)
        for (i in 0 until kept) {
            val x = newPts[2 * i]
            val y = newPts[2 * i + 1]
            val ddx = x - cx
            val ddy = y - cy
            if (ddx * ddx + ddy * ddy > lim) continue
            pts[2 * m] = x
            pts[2 * m + 1] = y
            m++
        }
        n = m
        prev = b

        // 4) too few corners: look for new ones inside the circle (not every frame)
        sinceReseed++
        val reseedBelow = max(MIN_POINTS + 4, (startCount * 0.85f).toInt())
        if (n < reseedBelow && sinceReseed >= RESEED_EVERY) {
            sinceReseed = 0
            detect(b[0])
        }
        return n >= MIN_POINTS
    }

    private data class Similarity(
        val a: Float, val b: Float, val tx: Float, val ty: Float,
        val inliers: Int, val tolerance: Float
    )

    /** Robustly estimates q = [a -b; b a] p + t from tracked feature pairs. */
    private fun estimateSimilarity(
        dxs: FloatArray, dys: FloatArray, ok: BooleanArray, good: Int
    ): Similarity? {
        if (good < MIN_POINTS) return null
        // Adapt the residual threshold slightly for larger inter-frame movement.
        val mdx = median(dxs, ok, good)
        val mdy = median(dys, ok, good)
        val tolerance = 2.75f + 0.025f * sqrt(mdx * mdx + mdy * mdy)
        val tol2 = tolerance * tolerance
        var bestCount = 0
        var bestError = Float.POSITIVE_INFINITY
        var ba = 1f; var bb = 0f; var btx = mdx; var bty = mdy

        // Deterministic pair hypotheses avoid random behaviour across runs/devices.
        for (i in 0 until n) {
            if (!ok[i]) continue
            val x1 = pts[2 * i]; val y1 = pts[2 * i + 1]
            val qx1 = x1 + dxs[i]; val qy1 = y1 + dys[i]
            for (j in i + 1 until n) {
                if (!ok[j]) continue
                val ox = pts[2 * j] - x1
                val oy = pts[2 * j + 1] - y1
                val denom = ox * ox + oy * oy
                if (denom < 16f) continue
                val nx = (pts[2 * j] + dxs[j]) - qx1
                val ny = (pts[2 * j + 1] + dys[j]) - qy1
                val a = (ox * nx + oy * ny) / denom
                val b = (ox * ny - oy * nx) / denom
                val scale2 = a * a + b * b
                if (scale2 < MIN_SCALE * MIN_SCALE || scale2 > MAX_SCALE * MAX_SCALE) continue
                val tx = qx1 - (a * x1 - b * y1)
                val ty = qy1 - (b * x1 + a * y1)
                var count = 0
                var error = 0f
                for (k in 0 until n) {
                    if (!ok[k]) continue
                    val x = pts[2 * k]; val y = pts[2 * k + 1]
                    val ex = x + dxs[k] - (a * x - b * y + tx)
                    val ey = y + dys[k] - (b * x + a * y + ty)
                    val e2 = ex * ex + ey * ey
                    if (e2 <= tol2) { count++; error += e2 }
                }
                if (count > bestCount || (count == bestCount && error < bestError)) {
                    bestCount = count; bestError = error
                    ba = a; bb = b; btx = tx; bty = ty
                }
            }
        }
        if (bestCount < MIN_POINTS) return null

        // Least-squares refinement over the winning consensus set.
        var sx = 0f; var sy = 0f; var sqx = 0f; var sqy = 0f; var count = 0
        for (i in 0 until n) {
            if (!ok[i]) continue
            val x = pts[2 * i]; val y = pts[2 * i + 1]
            val qx = x + dxs[i]; val qy = y + dys[i]
            val ex = qx - (ba * x - bb * y + btx)
            val ey = qy - (bb * x + ba * y + bty)
            if (ex * ex + ey * ey > tol2) continue
            sx += x; sy += y; sqx += qx; sqy += qy; count++
        }
        if (count < MIN_POINTS) return null
        val mx = sx / count; val my = sy / count
        val mqx = sqx / count; val mqy = sqy / count
        var den = 0f; var numA = 0f; var numB = 0f
        for (i in 0 until n) {
            if (!ok[i]) continue
            val x = pts[2 * i]; val y = pts[2 * i + 1]
            val qx = x + dxs[i]; val qy = y + dys[i]
            val ex = qx - (ba * x - bb * y + btx)
            val ey = qy - (bb * x + ba * y + bty)
            if (ex * ex + ey * ey > tol2) continue
            val px = x - mx; val py = y - my
            val ux = qx - mqx; val uy = qy - mqy
            den += px * px + py * py
            numA += px * ux + py * uy
            numB += px * uy - py * ux
        }
        if (den < 1e-4f) return null
        val a = numA / den
        val b = numB / den
        val tx = mqx - (a * mx - b * my)
        val ty = mqy - (b * mx + a * my)
        return Similarity(a, b, tx, ty, count, tolerance)
    }

    // ---- corner detection (Shi-Tomasi: smallest eigenvalue of the structure tensor) -------

    /** Adds corners inside the circle to [pts] (keeping the existing ones), up to [maxPoints]. */
    private fun detect(l: LkLevel) {
        val margin = WIN / 2 + 2
        val x0 = max(margin, (cx - radius).toInt())
        val y0 = max(margin, (cy - radius).toInt())
        val x1 = min(l.w - 1 - margin, (cx + radius).toInt())
        val y1 = min(l.h - 1 - margin, (cy + radius).toInt())
        if (x1 <= x0 || y1 <= y0) return
        val bw = x1 - x0 + 1
        val bh = y1 - y0 + 1

        // eigenvalue map of the box (5x5 window), normalised by the window area
        val win = 2
        val eig = FloatArray(bw * bh)
        val r2 = radius * radius
        for (y in y0..y1) {
            for (x in x0..x1) {
                val ddx = x - cx
                val ddy = y - cy
                if (ddx * ddx + ddy * ddy > r2) continue
                var gxx = 0f
                var gxy = 0f
                var gyy = 0f
                for (yy in y - win..y + win) {
                    val row = yy * l.w
                    for (xx in x - win..x + win) {
                        val gx = l.ix[row + xx]
                        val gy = l.iy[row + xx]
                        gxx += gx * gx
                        gxy += gx * gy
                        gyy += gy * gy
                    }
                }
                val tr = gxx + gyy
                val det = gxx * gyy - gxy * gxy
                val disc = sqrt(max(0f, tr * tr * 0.25f - det))
                eig[(y - y0) * bw + (x - x0)] = (tr * 0.5f - disc) / ((2 * win + 1) * (2 * win + 1))
            }
        }

        // best corner of every cell (cells are the minimum distance between corners)
        val cell = max(3f, radius * 0.18f)
        val cols = (bw / cell).toInt() + 1
        val rows = (bh / cell).toInt() + 1
        val bestEig = FloatArray(cols * rows)
        val bestX = IntArray(cols * rows)
        val bestY = IntArray(cols * rows)
        var maxEig = 0f
        for (y in 0 until bh) {
            for (x in 0 until bw) {
                val e = eig[y * bw + x]
                if (e <= 0f) continue
                val ci = (y / cell).toInt() * cols + (x / cell).toInt()
                if (e > bestEig[ci]) {
                    bestEig[ci] = e
                    bestX[ci] = x + x0
                    bestY[ci] = y + y0
                }
                if (e > maxEig) maxEig = e
            }
        }
        if (maxEig < MIN_EIG) return
        val floor = max(MIN_EIG, maxEig * 0.05f)
        val order = (0 until cols * rows).filter { bestEig[it] >= floor }.sortedByDescending { bestEig[it] }
        val minD2 = cell * cell * 0.5f
        for (ci in order) {
            if (n >= maxPoints) break
            val px = bestX[ci].toFloat()
            val py = bestY[ci].toFloat()
            var close = false
            for (j in 0 until n) {
                val ddx = pts[2 * j] - px
                val ddy = pts[2 * j + 1] - py
                if (ddx * ddx + ddy * ddy < minD2) {
                    close = true
                    break
                }
            }
            if (close) continue
            pts[2 * n] = px
            pts[2 * n + 1] = py
            n++
        }
    }

    // ---- pyramidal Lucas-Kanade for one point ----------------------------------------------

    /**
     * Where the point (x, y) of picture [a] is in picture [b]; written to [out]. False when the
     * point could not be followed (no texture, left the picture, did not settle).
     */
    private fun trackPoint(a: Array<LkLevel>, b: Array<LkLevel>, x: Float, y: Float, out: FloatArray): Boolean {
        var gx = 0f
        var gy = 0f
        val half = WIN / 2
        for (lv in a.size - 1 downTo 0) {
            val scale = 1f / (1 shl lv)
            val px = x * scale
            val py = y * scale
            val la = a[lv]
            val lb = b[lv]

            // template + structure tensor around the point in picture a
            var gxx = 0f
            var gxy = 0f
            var gyy = 0f
            var k = 0
            for (dy in -half..half) {
                for (dx in -half..half) {
                    val t = sample(la.img, la.w, la.h, px + dx, py + dy)
                    val ax = sample(la.ix, la.w, la.h, px + dx, py + dy)
                    val ay = sample(la.iy, la.w, la.h, px + dx, py + dy)
                    tpl[k] = t
                    tgx[k] = ax
                    tgy[k] = ay
                    gxx += ax * ax
                    gxy += ax * ay
                    gyy += ay * ay
                    k++
                }
            }
            val det = gxx * gyy - gxy * gxy
            if (lv == 0) {
                val tr = gxx + gyy
                val disc = sqrt(max(0f, tr * tr * 0.25f - det))
                val minEig = (tr * 0.5f - disc) / (WIN * WIN)
                if (minEig < MIN_EIG * 0.25f) return false
            }
            if (det < 1e-6f) {
                // flat at this scale: keep the guess, go to the finer level
                if (lv > 0) {
                    gx *= 2f
                    gy *= 2f
                }
                continue
            }

            var vx = 0f
            var vy = 0f
            for (iter in 0 until MAX_ITER) {
                val qx = px + gx + vx
                val qy = py + gy + vy
                if (qx < 0f || qy < 0f || qx > lb.w - 1f || qy > lb.h - 1f) return false
                var bx = 0f
                var by = 0f
                k = 0
                for (dy in -half..half) {
                    for (dx in -half..half) {
                        val d = tpl[k] - sample(lb.img, lb.w, lb.h, qx + dx, qy + dy)
                        bx += d * tgx[k]
                        by += d * tgy[k]
                        k++
                    }
                }
                val ddx = (gyy * bx - gxy * by) / det
                val ddy = (-gxy * bx + gxx * by) / det
                vx += ddx
                vy += ddy
                if (ddx * ddx + ddy * ddy < EPS * EPS) break
            }
            gx += vx
            gy += vy
            if (lv > 0) {
                gx *= 2f
                gy *= 2f
            }
        }
        val rx = x + gx
        val ry = y + gy
        val w0 = a[0].w
        val h0 = a[0].h
        if (rx < 0f || ry < 0f || rx > w0 - 1f || ry > h0 - 1f) return false

        // Appearance check: the patch must still look like itself (normalised cross-correlation,
        // so a change of brightness is fine). Catches a covered or changed object, which the
        // forward-backward check alone does not.
        val l0 = b[0]
        var sa = 0f
        var sb = 0f
        var saa = 0f
        var sbb = 0f
        var sab = 0f
        var k = 0
        for (dy in -half..half) {
            for (dx in -half..half) {
                val t = tpl[k]
                val q = sample(l0.img, l0.w, l0.h, rx + dx, ry + dy)
                sa += t
                sb += q
                saa += t * t
                sbb += q * q
                sab += t * q
                k++
            }
        }
        val cnt = (WIN * WIN).toFloat()
        val va = saa - sa * sa / cnt
        val vb = sbb - sb * sb / cnt
        if (va <= 1e-3f || vb <= 1e-3f) return false
        val ncc = (sab - sa * sb / cnt) / sqrt(va * vb)
        if (ncc < MIN_NCC) return false

        out[0] = rx
        out[1] = ry
        return true
    }

    companion object {
        /** Tracking window (odd): 15 x 15 pixels. */
        private const val WIN = 15
        private const val MAX_ITER = 20
        private const val EPS = 0.03f

        /** Forward-backward error allowed, in picture pixels. */
        private const val FB_TOL = 1.5f

        /** Least corners needed to trust the motion. */
        const val MIN_POINTS = 4

        /** Frames in a row that may fail before the object is declared lost. */
        const val MAX_MISSES = 3

        /** Weakest accepted corner (smallest eigenvalue per window pixel; about 2 grey levels per pixel). */
        private const val MIN_EIG = 4f
        private const val RESEED_EVERY = 2
        private const val MIN_SCALE = 0.65f
        private const val MAX_SCALE = 1.55f

        /** Least patch similarity (-1..1) for a point to count as followed. */
        private const val MIN_NCC = 0.5f

        internal fun buildPyramid(src: GrayImage, maxLevels: Int = 4): Array<LkLevel> {
            val out = ArrayList<LkLevel>()
            var w = src.w
            var h = src.h
            var data = src.px
            while (true) {
                out.add(makeLevel(w, h, data))
                if (out.size >= maxLevels || min(w, h) < 64) break
                val w2 = w / 2
                val h2 = h / 2
                val d2 = FloatArray(w2 * h2)
                for (y in 0 until h2) {
                    val r0 = 2 * y * w
                    val r1 = r0 + w
                    for (x in 0 until w2) {
                        val i = 2 * x
                        d2[y * w2 + x] = (data[r0 + i] + data[r0 + i + 1] + data[r1 + i] + data[r1 + i + 1]) * 0.25f
                    }
                }
                w = w2
                h = h2
                data = d2
            }
            return out.toTypedArray()
        }

        private fun makeLevel(w: Int, h: Int, img: FloatArray): LkLevel {
            val ix = FloatArray(w * h)
            val iy = FloatArray(w * h)
            for (y in 1 until h - 1) {
                val row = y * w
                for (x in 1 until w - 1) {
                    ix[row + x] = (img[row + x + 1] - img[row + x - 1]) * 0.5f
                    iy[row + x] = (img[row + w + x] - img[row - w + x]) * 0.5f
                }
            }
            return LkLevel(w, h, img, ix, iy)
        }

        /** Bilinear read; positions outside are held at the border. */
        private fun sample(a: FloatArray, w: Int, h: Int, x0: Float, y0: Float): Float {
            val x = if (x0 < 0f) 0f else if (x0 > w - 1.001f) w - 1.001f else x0
            val y = if (y0 < 0f) 0f else if (y0 > h - 1.001f) h - 1.001f else y0
            val xi = x.toInt()
            val yi = y.toInt()
            val fx = x - xi
            val fy = y - yi
            val i = yi * w + xi
            return a[i] * (1f - fx) * (1f - fy) + a[i + 1] * fx * (1f - fy) +
                a[i + w] * (1f - fx) * fy + a[i + w + 1] * fx * fy
        }

        /** Median of the entries flagged in [use] ([count] of them). */
        private fun median(v: FloatArray, use: BooleanArray, count: Int): Float {
            val t = FloatArray(count)
            var k = 0
            for (i in v.indices) if (use[i]) t[k++] = v[i]
            t.sort()
            return if (count % 2 == 1) t[count / 2] else (t[count / 2 - 1] + t[count / 2]) * 0.5f
        }
    }
}
