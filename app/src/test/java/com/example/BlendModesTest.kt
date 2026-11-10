package com.example

import com.example.model.BlendKind
import com.example.model.BlendModes
import com.example.model.LayerType
import com.example.model.TimelineClip
import com.example.model.hasBlending
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlendModesTest {
    private fun clip(opacity: Float = 100f, mode: String = "Normal") = TimelineClip(
        id = "c", name = "c", type = LayerType.SHAPE, startMs = 0, durationMs = 1000, color = 0,
        opacity = opacity, blendMode = mode
    )

    @Test
    fun unknownNameFallsBackToNormal() {
        assertTrue(BlendModes.find("does not exist").isNormal)
        assertTrue(BlendModes.find(null).isNormal)
    }

    @Test
    fun shaderIdsAreUniqueAndNamesUnique() {
        assertEquals(BlendModes.all.size, BlendModes.all.map { it.shaderId }.toSet().size)
        assertEquals(BlendModes.all.size, BlendModes.all.map { it.name }.toSet().size)
    }

    @Test
    fun everyCategoryHasModesAndEveryModeHasACategory() {
        BlendModes.categories.forEach { assertTrue(it, BlendModes.inCategory(it).isNotEmpty()) }
        BlendModes.all.forEach { assertTrue(it.name, it.category in BlendModes.categories) }
    }

    @Test
    fun mattesAreInTheMatteGroup() {
        BlendModes.all.filter { it.kind == BlendKind.MATTE }
            .forEach { assertEquals("Matte", it.category) }
    }

    @Test
    fun hasBlendingNeedsOpacityOrMode() {
        assertFalse(clip().hasBlending)
        assertTrue(clip(opacity = 80f).hasBlending)
        assertTrue(clip(mode = "Multiply").hasBlending)
    }
}
