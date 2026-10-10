package com.example

import com.example.engine.ExportSizes
import com.example.model.AspectRatio
import org.junit.Assert.assertEquals
import org.junit.Test

/** The export dialog and the encoder must agree on the output size, so pin the maths here. */
class ExportSizesTest {

    @Test
    fun shortSideComesFromTheResolutionLabel() {
        assertEquals(1920 to 1080, ExportSizes.forResolution("1080p FHD", AspectRatio.RATIO_16_9))
        assertEquals(1280 to 720, ExportSizes.forResolution("720p", AspectRatio.RATIO_16_9))
        assertEquals(852 to 480, ExportSizes.forResolution("480p", AspectRatio.RATIO_16_9))
    }

    @Test
    fun portraitRatiosFlipTheSides() {
        assertEquals(1080 to 1920, ExportSizes.forResolution("1080p FHD", AspectRatio.RATIO_9_16))
        assertEquals(720 to 900, ExportSizes.forResolution("720p", AspectRatio.RATIO_4_5))
    }

    @Test
    fun squareAndUltrawideStayConsistent() {
        assertEquals(720 to 720, ExportSizes.forResolution("720p", AspectRatio.RATIO_1_1))
        assertEquals(2520 to 1080, ExportSizes.forResolution("1080p FHD", AspectRatio.RATIO_21_9))
    }

    @Test
    fun bothSidesAreAlwaysEven() {
        AspectRatio.values().forEach { ratio ->
            val (w, h) = ExportSizes.forResolution("1080p FHD", ratio)
            assertEquals("$ratio width must be even", 0, w % 2)
            assertEquals("$ratio height must be even", 0, h % 2)
        }
    }

    @Test
    fun unknownLabelFallsBackTo1080() {
        assertEquals(1920 to 1080, ExportSizes.forResolution("High", AspectRatio.RATIO_16_9))
    }
}
