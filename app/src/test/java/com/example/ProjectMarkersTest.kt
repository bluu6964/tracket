package com.example

import com.example.model.ProjectMarkers
import com.example.model.ProjectSnapshot
import com.example.model.TimelineMarker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The named-marker list rules (model/ProjectMarkers.kt, build 188): sorted storage,
 * trimmed non-blank names, add / rename / remove, and the nearest-within-tolerance
 * lookup that drives the ruler tap and the marker button. Plus the project-file side:
 * a snapshot without the field (every file written before build 188) defaults to no
 * markers, and a marker list survives a snapshot copy round-trip unchanged.
 */
class ProjectMarkersTest {

    private fun m(id: String, t: Long, name: String = id) = TimelineMarker(id, t, name)

    @Test
    fun addKeepsTheListSortedByTime() {
        var list = emptyList<TimelineMarker>()
        list = ProjectMarkers.add(list, 3000, "Outro", id = "c")
        list = ProjectMarkers.add(list, 1000, "Intro", id = "a")
        list = ProjectMarkers.add(list, 2000, "Drop", id = "b")
        assertEquals(listOf("a", "b", "c"), list.map { it.id })
        assertEquals(listOf(1000L, 2000L, 3000L), list.map { it.timeMs })
    }

    @Test
    fun addTrimsTheNameAndFallsBackWhenBlank() {
        val list = ProjectMarkers.add(emptyList(), 500, "  Verse  ", id = "a")
        assertEquals("Verse", list.single().name)
        val blank = ProjectMarkers.add(emptyList(), 500, "   ", id = "b")
        assertEquals(ProjectMarkers.FALLBACK_NAME, blank.single().name)
    }

    @Test
    fun addClampsANegativeTimeToZero() {
        val list = ProjectMarkers.add(emptyList(), -250, "Start", id = "a")
        assertEquals(0L, list.single().timeMs)
    }

    @Test
    fun renameChangesOnlyTheNamedMarker() {
        val list = listOf(m("a", 1000), m("b", 2000))
        val out = ProjectMarkers.rename(list, "b", "Chorus")
        assertEquals("a", out[0].name)
        assertEquals("Chorus", out[1].name)
        // A blank rename is refused (a marker never loses its label); unknown id = no-op.
        assertEquals(list, ProjectMarkers.rename(list, "b", "   "))
        assertEquals(list, ProjectMarkers.rename(list, "zzz", "X"))
    }

    @Test
    fun removeDropsOnlyThatMarker() {
        val list = listOf(m("a", 1000), m("b", 2000), m("c", 3000))
        assertEquals(listOf("a", "c"), ProjectMarkers.remove(list, "b").map { it.id })
        assertEquals(list, ProjectMarkers.remove(list, "zzz"))
    }

    @Test
    fun nearestFindsTheClosestMarkerInsideTheToleranceOnly() {
        val list = listOf(m("a", 1000), m("b", 5000), m("c", 9000))
        assertEquals("b", ProjectMarkers.nearest(list, 4800, 300)?.id)
        assertEquals("b", ProjectMarkers.nearest(list, 5300, 300)?.id)   // exactly at the edge
        assertNull(ProjectMarkers.nearest(list, 5301, 300))
        assertNull(ProjectMarkers.nearest(list, 3000, 300))
        assertNull(ProjectMarkers.nearest(emptyList(), 1000, 300))
    }

    @Test
    fun normalizeSortsAndDropsNegativeTimes() {
        val messy = listOf(m("c", 3000), m("bad", -5), m("a", 1000))
        assertEquals(listOf("a", "c"), ProjectMarkers.normalize(messy).map { it.id })
    }

    @Test
    fun snapshotDefaultsToNoMarkersAndKeepsThemOnCopy() {
        // Every project file written before build 188 lacks the "markers" key; the
        // snapshot default is what those files load with.
        assertTrue(ProjectSnapshot().markers.isEmpty())
        val list = listOf(m("a", 1000, "Intro"), m("b", 2000, "Drop"))
        val snap = ProjectSnapshot(markers = list)
        assertEquals(list, snap.copy().markers)
    }
}
