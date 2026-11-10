package com.example

import com.example.engine.FaceRegion
import com.example.engine.FaceRegions
import com.example.engine.Pt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceRegionsTest {

    @Test
    fun hullOfSquareWithInnerPointIsTheSquare() {
        val pts = listOf(Pt(0f, 0f), Pt(10f, 0f), Pt(10f, 10f), Pt(0f, 10f), Pt(5f, 5f), Pt(2f, 7f))
        val hull = FaceRegions.convexHull(pts)
        assertEquals(4, hull.size)
        assertTrue(hull.none { it.x == 5f && it.y == 5f })
    }

    @Test
    fun growKeepsCentreAndScalesDistance() {
        val hull = listOf(Pt(0f, 0f), Pt(10f, 0f), Pt(10f, 10f), Pt(0f, 10f))
        val g = FaceRegions.grow(hull, 2f)
        assertEquals(-5f, g.minOf { it.x }, 0.001f)
        assertEquals(15f, g.maxOf { it.x }, 0.001f)
    }

    @Test
    fun everyRegionGivesShapesForAFullMesh() {
        // 478 points on a small circle: only the numbering and the counts are checked here.
        val lm: (Int) -> Pair<Float, Float> = { i ->
            val a = i * 0.0131f
            Pair(0.5f + 0.2f * kotlin.math.cos(a), 0.5f + 0.2f * kotlin.math.sin(a))
        }
        FaceRegion.values().forEach { r ->
            val polys = FaceRegions.polygons(r, 478, 256, 144, lm)
            assertEquals(if (r == FaceRegion.EYES) 2 else 1, polys.size)
            assertTrue(polys.all { it.first.size >= 3 })
        }
    }

    @Test
    fun faceWithTooFewPointsIsIgnored() {
        assertTrue(FaceRegions.polygons(FaceRegion.LIPS, 100, 256, 144) { Pair(0.5f, 0.5f) }.isEmpty())
    }

    @Test
    fun unknownRegionNameFallsBackToFace() {
        assertEquals(FaceRegion.FACE, FaceRegion.fromName("xyz"))
        assertEquals(FaceRegion.LIPS, FaceRegion.fromName("LIPS"))
    }
}
