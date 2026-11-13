package com.example

import com.example.engine.CounterEval
import com.example.engine.ParentRig
import com.example.model.FILL_IMAGE
import com.example.model.LayerType
import com.example.model.SocialGrowthSpec
import com.example.model.SocialGrowthTemplate
import com.example.model.renderAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The "Social Growth" template (Templates programme, reference commission batch 2):
 * ONE group holding the UPLOAD pill, the trim-drawn progress ring with its shape
 * checkmark, the CREATE / POST... / ELEVATE titles, the profile header (avatar slot
 * + two counter texts) and the 3 x 4 tile grid with shape checkmarks. The pins
 * cover the structure, the title windows (exactly one title at a time, ELEVATE
 * green), the counter values read off the reference frames evaluated through the
 * real CounterEval (posts 0 -> 59, followers 0 -> 9716, same slow-then-fast
 * pacing), the ring's trim law, the tiles' staggered pop-in at their grid
 * positions, and two-insert independence.
 */
class SocialGrowthTemplateTest {

    private val pw = 720f
    private val ph = 1280f
    private val at0 = 2000L
    private val ins = SocialGrowthTemplate.buildGroup(pw, ph, "file:///me.png", at0, idBase = "test")

    private val rig = ParentRig(ins.lanesTopFirst) { c, t -> c.renderAt(t) }
    private fun at(clip: com.example.model.TimelineClip, relMs: Long) = rig.adjusted(clip, at0 + relMs)
    private fun shown(clip: com.example.model.TimelineClip, relMs: Long): String =
        CounterEval.displayText(clip.text ?: "0", clip, at0 + relMs) ?: "?"

    @Test
    fun structureOneGroupFullInventory() {
        assertEquals(LayerType.GROUP, ins.group.type)
        assertEquals("Social Growth", ins.group.name)
        assertEquals(SocialGrowthSpec.DURATION_MS, ins.group.durationMs)
        // bg + 3 titles + pill + label + ring + 2 ring-check + avatar + 2 rings
        // + 2 counters + 2 labels + 12 tiles + 24 tile-check
        assertEquals(52, ins.children.size)
        assertEquals(53, ins.lanesTopFirst.size)
        for (c in ins.children) {
            assertEquals(ins.group.id, c.groupId)
            assertEquals(ins.group.id, c.parent?.id)
            assertTrue(c.controllerId == null)
        }
        assertEquals(listOf("CREATE", "POST...", "ELEVATE"), ins.titles.map { it.text })
        assertEquals("UPLOAD", ins.uploadText.text)
        assertEquals(FILL_IMAGE, ins.avatar.fillMode)
        assertEquals("file:///me.png", ins.avatar.fillImageUri)
        assertEquals("POSTS", ins.postsLabel.text)
        assertEquals("FOLLOWERS", ins.followersLabel.text)
        assertEquals(12, ins.tiles.size)
        assertEquals(12, ins.tileChecks.size)
        assertTrue(ins.tileChecks.all { it.size == 2 })
        assertEquals(2, ins.ringCheck.size)
    }

    @Test
    fun titleWindowsExactlyOneAtATime() {
        fun visibleAt(t: Long) = ins.titles.indices.filter { at(ins.titles[it], t).opacity > 50f }
        assertEquals(listOf(0), visibleAt(3000L))
        assertEquals(listOf(1), visibleAt(6500L))
        assertEquals(listOf(2), visibleAt(9000L))
        assertEquals(SocialGrowthSpec.TITLE_GREEN, ins.titles[2].textColor)
        assertEquals(0xFFFFFFFFL, ins.titles[0].textColor)
    }

    @Test
    fun countersClimbWithTheVideosPacing() {
        // The reference frames' own readings (see the spec): slow at first, then fast.
        assertEquals("0", shown(ins.postsCount, 5200L))
        assertEquals("2", shown(ins.postsCount, 6200L))
        assertEquals("24", shown(ins.postsCount, 8500L))
        assertEquals("59", shown(ins.postsCount, 10000L))
        assertEquals("3", shown(ins.followersCount, 6200L))
        assertEquals("13", shown(ins.followersCount, 7000L))
        assertEquals("896", shown(ins.followersCount, 8500L))
        assertEquals("3938", shown(ins.followersCount, 9200L))
        assertEquals("9716", shown(ins.followersCount, 10000L))
    }

    @Test
    fun uploadPillThenRingDrawsItself() {
        assertTrue(at(ins.uploadPill, 1500L).opacity > 50f)
        assertTrue(at(ins.uploadPill, 3000L).opacity < 50f)
        val ringStart = at(ins.ring, 2600L)
        assertEquals(0f, ringStart.strokeTrimEnd - ringStart.strokeTrimStart, 0.01f)
        val ringMid = at(ins.ring, 3600L)
        val swept = ringMid.strokeTrimEnd - ringMid.strokeTrimStart
        assertTrue("ring mid-draw (was $swept)", swept in 0.45f..0.85f)
        val ringFull = at(ins.ring, 4300L)
        assertEquals(1f, ringFull.strokeTrimEnd - ringFull.strokeTrimStart, 0.01f)
        assertTrue("the check lands as the ring completes", at(ins.ringCheck[0], 4500L).opacity > 50f)
    }

    @Test
    fun tilesPopInOneByOneAtTheirGridPositions() {
        for ((i, tile) in ins.tiles.withIndex()) {
            val (tx, ty) = SocialGrowthSpec.tilePos(i, pw, ph)
            assertEquals(tx, tile.transformX, 0.5f)
            assertEquals(ty, tile.transformY, 0.5f)
            val tin = SocialGrowthSpec.TILE_IN_FIRST_MS + i * SocialGrowthSpec.TILE_IN_STAGGER_MS
            assertTrue("tile $i hidden before its turn", at(tile, tin - 1L).opacity < 50f)
            assertTrue("tile $i shown after its pop", at(tile, tin + SocialGrowthSpec.TILE_IN_MS + 10L).opacity > 50f)
            // Its checkmark follows it in.
            assertTrue("tile $i check shown", at(ins.tileChecks[i][0], tin + SocialGrowthSpec.TILE_IN_MS + 100L).opacity > 50f)
        }
        // Grid bounds on the native canvas: everything inside the frame.
        for (tile in ins.tiles) {
            assertTrue(abs(tile.transformX) + SocialGrowthSpec.TILE_FRAC * pw / 2f <= pw / 2f)
            assertTrue(abs(tile.transformY) + SocialGrowthSpec.TILE_FRAC * pw / 2f <= ph / 2f)
        }
    }

    @Test
    fun headerFadesInAndTheRingTurnsGreen() {
        assertTrue(at(ins.avatar, 5000L).opacity < 50f)
        assertTrue(at(ins.avatar, 6000L).opacity > 50f)
        assertTrue("grey ring first", at(ins.avatarRing, 6000L).opacity > 50f)
        assertTrue("green ring takes over with ELEVATE", at(ins.avatarRingGreen, 9000L).opacity > 50f)
        assertTrue(at(ins.avatarRing, 9000L).opacity < 50f)
    }

    @Test
    fun twoInsertsAreIndependent() {
        val other = SocialGrowthTemplate.buildGroup(pw, ph, "file:///me.png", at0, idBase = "other")
        val idsA = ins.lanesTopFirst.map { it.id }.toSet()
        val idsB = other.lanesTopFirst.map { it.id }.toSet()
        assertTrue(idsA.intersect(idsB).isEmpty())
        assertEquals(ins.children.size, other.children.size)
    }
}
