package com.example

import com.example.model.ClipFxLibrary
import com.example.model.FxLists
import com.example.model.FxTileArt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Build 200 — the Effects layout redesign's pure pieces: the Favorites / Recent
 * list laws behind the browser chips ([FxLists]) and the deterministic tile
 * variation seed ([FxTileArt]). The UI (SharedPreferences storage, chips, grid)
 * rides these exactly.
 */
class FxListsTest {

    @Test
    fun parseDropsBlanksAndDuplicates() {
        assertEquals(emptyList<String>(), FxLists.parse(null))
        assertEquals(emptyList<String>(), FxLists.parse(""))
        assertEquals(emptyList<String>(), FxLists.parse("   "))
        assertEquals(listOf("a", "b"), FxLists.parse("a,b"))
        assertEquals(listOf("a", "b"), FxLists.parse(" a , ,b,,a, b "))
    }

    @Test
    fun serializeRoundTripsThroughParse() {
        val ids = listOf("gaussian_blur", "rgb_split", "glow")
        assertEquals("gaussian_blur,rgb_split,glow", FxLists.serialize(ids))
        assertEquals(ids, FxLists.parse(FxLists.serialize(ids)))
        assertEquals("", FxLists.serialize(emptyList()))
    }

    @Test
    fun toggleFavoriteAddsThenRemoves() {
        val one = FxLists.toggleFavorite(emptyList(), "glow")
        assertEquals(listOf("glow"), one)
        val two = FxLists.toggleFavorite(one, "vignette")
        assertEquals(listOf("glow", "vignette"), two)
        assertEquals(listOf("vignette"), FxLists.toggleFavorite(two, "glow"))
        assertEquals(emptyList<String>(), FxLists.toggleFavorite(one, "glow"))
    }

    @Test
    fun pushRecentPutsNewestFirstAndDedupes() {
        var r = FxLists.pushRecent(emptyList(), "a")
        r = FxLists.pushRecent(r, "b")
        r = FxLists.pushRecent(r, "c")
        assertEquals(listOf("c", "b", "a"), r)
        // Re-applying an older effect moves it to the front, no duplicate.
        r = FxLists.pushRecent(r, "a")
        assertEquals(listOf("a", "c", "b"), r)
    }

    @Test
    fun pushRecentCapsAtTheCap() {
        var r = emptyList<String>()
        for (i in 0 until 40) r = FxLists.pushRecent(r, "fx_$i")
        assertEquals(FxLists.RECENT_CAP, r.size)
        assertEquals("fx_39", r.first())
        assertEquals("fx_${40 - FxLists.RECENT_CAP}", r.last())
        // A smaller explicit cap is honoured too.
        assertEquals(3, FxLists.pushRecent((0 until 10).map { "x_$it" }, "new", cap = 3).size)
    }

    @Test
    fun favoriteDefsFollowLibraryOrderAndIgnoreUnknownIds() {
        val all = ClipFxLibrary.all
        val some = FxLists.favoriteDefs(all, listOf("no_such_effect", all.last().id, all.first().id))
        assertEquals(listOf(all.first(), all.last()), some)
        assertTrue(FxLists.favoriteDefs(all, emptyList()).isEmpty())
    }

    @Test
    fun recentDefsFollowRecentOrderAndDropUnknownIds() {
        val all = ClipFxLibrary.all
        val a = all[0]
        val b = all[5]
        val c = all[10]
        assertEquals(listOf(c, a), FxLists.recentDefs(all, listOf(c.id, "gone", a.id)))
        assertEquals(listOf(b), FxLists.recentDefs(all, listOf(b.id)))
        assertTrue(FxLists.recentDefs(all, emptyList()).isEmpty())
    }

    @Test
    fun tileSeedIsDeterministicAndPerEffect() {
        val blur1 = FxTileArt.seed(ClipFxLibrary.BLUR)
        assertEquals(blur1, FxTileArt.seed(ClipFxLibrary.BLUR))
        // A sample of real library ids must not all collide onto one tile seed.
        val seeds = ClipFxLibrary.all.take(24).map { FxTileArt.seed(it.id) }.toSet()
        assertTrue("expected varied seeds, got $seeds", seeds.size >= 20)
        assertFalse(FxTileArt.seed(ClipFxLibrary.GLOW) == FxTileArt.seed(ClipFxLibrary.VIGNETTE) &&
            FxTileArt.seed(ClipFxLibrary.GLOW) == FxTileArt.seed(ClipFxLibrary.GRAIN))
    }

    @Test
    fun tileUnitStaysInRange() {
        val seed = FxTileArt.seed("rgb_split")
        for (salt in 0 until 64) {
            val u = FxTileArt.unit(seed, salt)
            assertTrue("unit($salt) = $u", u >= 0f && u < 1f)
        }
        // Different salts give different draws (the artwork's blobs/stars must scatter).
        val draws = (0 until 12).map { FxTileArt.unit(seed, it) }.toSet()
        assertTrue(draws.size >= 10)
    }

    @Test
    fun tileHueShiftStaysInsideItsBand() {
        for (def in ClipFxLibrary.all) {
            val s = FxTileArt.hueShiftDeg(def.id)
            assertTrue("${def.id} shift $s", s >= -24f && s < 24f)
        }
        // And it actually varies across the library (not a constant).
        val shifts = ClipFxLibrary.all.take(24).map { FxTileArt.hueShiftDeg(it.id) }.toSet()
        assertTrue(shifts.size >= 16)
    }
}
