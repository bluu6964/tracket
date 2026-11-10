package com.example

import com.example.engine.CircleTracker
import com.example.engine.GrayImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class LkTrackerTest {

    private val w = 320
    private val h = 240

    /** Smooth random texture (values 0..255): something Lucas-Kanade can lock onto. */
    private fun texture(seed: Int): FloatArray {
        val rnd = Random(seed)
        var a = FloatArray(w * h) { rnd.nextFloat() }
        repeat(2) {
            val b = FloatArray(w * h)
            for (y in 2 until h - 2) for (x in 2 until w - 2) {
                var s = 0f
                for (dy in -2..2) for (dx in -2..2) s += a[(y + dy) * w + x + dx]
                b[y * w + x] = s / 25f
            }
            a = b
        }
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (v in a) { lo = minOf(lo, v); hi = maxOf(hi, v) }
        return FloatArray(w * h) { (a[it] - lo) / (hi - lo) * 255f }
    }

    /** The texture moved by (dx, dy) pixels (bilinear, border held). */
    private fun shifted(base: FloatArray, dx: Float, dy: Float): GrayImage {
        val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val sx = (x - dx).coerceIn(0f, w - 1.001f)
            val sy = (y - dy).coerceIn(0f, h - 1.001f)
            val xi = sx.toInt(); val yi = sy.toInt()
            val fx = sx - xi; val fy = sy - yi
            val i = yi * w + xi
            out[y * w + x] = base[i] * (1 - fx) * (1 - fy) + base[i + 1] * fx * (1 - fy) +
                base[i + w] * (1 - fx) * fy + base[i + w + 1] * fx * fy
        }
        return GrayImage(w, h, out)
    }

    @Test
    fun followsSteadyMotion() {
        val base = texture(7)
        val t = CircleTracker()
        assertTrue(t.start(shifted(base, 0f, 0f), 120f, 100f, 40f))
        for (k in 1..20) {
            assertTrue("frame $k", t.step(shifted(base, 2.5f * k, -1.5f * k)))
        }
        assertEquals(120f + 50f, t.cx, 1.0f)
        assertEquals(100f - 30f, t.cy, 1.0f)
    }

    @Test
    fun followsFastMotion() {
        val base = texture(11)
        val t = CircleTracker()
        assertTrue(t.start(shifted(base, 0f, 0f), 100f, 120f, 35f))
        for (k in 1..8) {
            assertTrue("frame $k", t.step(shifted(base, 9f * k, 4f * k)))
        }
        assertEquals(100f + 72f, t.cx, 1.5f)
        assertEquals(120f + 32f, t.cy, 1.5f)
    }


    /** Applies a small similarity transform around the image centre using bilinear sampling. */
    private fun transformed(base: FloatArray, scale: Float, angle: Float): GrayImage {
        val out = FloatArray(w * h)
        val c = kotlin.math.cos(angle)
        val s = kotlin.math.sin(angle)
        val mx = w / 2f
        val my = h / 2f
        for (y in 0 until h) for (x in 0 until w) {
            val dx = (x - mx) / scale
            val dy = (y - my) / scale
            val sx = (c * dx + s * dy + mx).coerceIn(0f, w - 1.001f)
            val sy = (-s * dx + c * dy + my).coerceIn(0f, h - 1.001f)
            val xi = sx.toInt(); val yi = sy.toInt()
            val fx = sx - xi; val fy = sy - yi
            val i = yi * w + xi
            out[y * w + x] = base[i] * (1 - fx) * (1 - fy) + base[i + 1] * fx * (1 - fy) +
                base[i + w] * (1 - fx) * fy + base[i + w + 1] * fx * fy
        }
        return GrayImage(w, h, out)
    }

    @Test
    fun tracksSmallRotationAndScaleChanges() {
        val base = texture(31)
        val t = CircleTracker()
        assertTrue(t.start(GrayImage(w, h, base), 150f, 110f, 40f))
        assertTrue(t.step(transformed(base, 1.06f, 0.06f)))
        // Transform is around (160,120); expected point is transformed by scale and rotation.
        val ex = 160f + 1.06f * (kotlin.math.cos(0.06f) * -10f - kotlin.math.sin(0.06f) * -10f)
        val ey = 120f + 1.06f * (kotlin.math.sin(0.06f) * -10f + kotlin.math.cos(0.06f) * -10f)
        assertEquals(ex, t.cx, 2.5f)
        assertEquals(ey, t.cy, 2.5f)
    }

    @Test
    fun reportsTurnAndZoomOfTheObject() {
        val base = texture(31)
        val t = CircleTracker()
        assertTrue(t.start(GrayImage(w, h, base), 150f, 110f, 40f))
        assertEquals(0f, t.rotationDeg, 0f)
        assertEquals(1f, t.scaleTotal, 0f)
        assertTrue(t.step(transformed(base, 1.06f, 0.06f)))
        assertEquals(Math.toDegrees(0.06).toFloat(), t.rotationDeg, 0.8f)
        assertEquals(1.06f, t.scaleTotal, 0.03f)
    }

    @Test
    fun plainAreaCannotBeFollowed() {
        val flat = GrayImage(w, h, FloatArray(w * h) { 128f })
        assertFalse(CircleTracker().start(flat, 160f, 120f, 40f))
    }

    @Test
    fun reportsLossWhenTheSceneChangesCompletely() {
        val t = CircleTracker()
        assertTrue(t.start(shifted(texture(3), 0f, 0f), 160f, 120f, 40f))
        // a few bad frames are bridged, then the object counts as lost
        var alive = true
        repeat(CircleTracker.MAX_MISSES + 1) { alive = t.step(shifted(texture(99), 0f, 0f)) }
        assertFalse(alive)
    }

    @Test
    fun cornersAreReplacedWhenTheyDrop() {
        // the object keeps moving for a long time: the circle must still be on it at the end
        val base = texture(21)
        val t = CircleTracker()
        assertTrue(t.start(shifted(base, 0f, 0f), 80f, 120f, 30f))
        for (k in 1..40) {
            assertTrue("frame $k", t.step(shifted(base, 4f * k, 0f)))
        }
        assertTrue(abs(t.cx - (80f + 160f)) < 3f)
        assertTrue(t.pointCount >= CircleTracker.MIN_POINTS)
    }

    @Test
    fun survivesNoiseAndBrightnessChange() {
        val base = texture(5)
        val rnd = Random(1)
        fun frame(dx: Float, dy: Float): GrayImage {
            val g = shifted(base, dx, dy)
            return GrayImage(w, h, FloatArray(w * h) {
                (g.px[it] * 1.15f + 12f + (rnd.nextFloat() - 0.5f) * 8f).coerceIn(0f, 255f)
            })
        }
        val t = CircleTracker()
        assertTrue(t.start(frame(0f, 0f), 150f, 110f, 40f))
        for (k in 1..24) assertTrue("frame $k", t.step(frame(3f * k, 2f * k)))
        assertEquals(150f + 72f, t.cx, 1.5f)
        assertEquals(110f + 48f, t.cy, 1.5f)
    }
}
