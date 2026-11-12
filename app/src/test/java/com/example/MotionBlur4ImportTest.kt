package com.example

import com.example.engine.AlightXmlImporter
import com.example.engine.AmFxEval
import com.example.engine.MotionBlurEval
import com.example.model.ClipFx
import com.example.model.ClipFxLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Build 136: Alight's `motionblur4` imports as its own effect and resolves per frame
 * into the def's three stages (position / scale / angle — kFxBlur2 modes 10..12),
 * instead of the generic motion-blur approximation the older ids keep. The def was
 * recovered in hunt round 3 (hunt-staging/defs-batch8/def_motionblur4_truemotion.json,
 * AM's own motionblur4.xml): its animate() pushes the position group, then the scale
 * group, then the angle group, each gated on its use* flag and a |velocity| > 0.0001
 * test; samplesMin / samplesMax default to 1 / 100 and the corpus serialises them
 * only at those defaults (tune 0.3..2.2+, 86 instances keyframed).
 */
class MotionBlur4ImportTest {

    private fun scene(layers: String): String =
        """<?xml version='1.0' encoding='UTF-8' ?><!-- test -->
<scene title="MB4" width="1080" height="1920" bgcolor="#ff102030" totalTime="2000" fps="30">
$layers
</scene>"""

    private fun shapeWith(fx: String): String = scene(
        """<shape id="1" label="Box" startTime="0" endTime="2000" fillType="color" s=".rect">
  <transform><location value="540,960,0" /><scale value="1,1" /></transform>
  <fillColor value="#ffffffff" />
  $fx
  <property name="size" type="vec2" value="100,100" /></shape>"""
    )

    private fun importFx(fxXml: String) =
        AlightXmlImporter.parse(shapeWith(fxXml), 1080f).let { r ->
            r to r.snapshot.tracks.flatMap { it.clips }[0].effects.single { it.type == ClipFxLibrary.MOTION_BLUR4 }
        }

    @Test
    fun importsAsItsOwnEffectWithTheDefSchema() {
        // omniframe-mi-mi-mi-pf.xml, verbatim (tune only; the flags ride the def defaults).
        val (r, fx) = importFx(
            """<effect id="com.alightcreative.effects.motionblur4" locallyApplied="true">
      <property name="tune" type="float" value="0.750000"/>
    </effect>"""
        )
        // No "mapped approximately" verdict any more — the def is ported.
        assertTrue(r.warnings.none { it.contains("motionblur4") })
        assertEquals(75f, fx.values[0], 0.001f)          // tune x100 (the def range is 0..4)
        assertEquals(1f, fx.values[1], 0.001f)           // usePos (def default true)
        assertEquals(1f, fx.values[2], 0.001f)           // useScale
        assertEquals(1f, fx.values[3], 0.001f)           // useAngle
        assertEquals(1f, fx.values[4], 0.001f)           // samplesMin (def default)
        assertEquals(100f, fx.values[5], 0.001f)         // samplesMax (def default)
        // The stored effect itself never reaches the engine: toNative drops it.
        assertEquals(null, ClipFxLibrary.toNative(listOf(fx)))
    }

    @Test
    fun flagsAndSampleBoundsAreStored() {
        val (_, fx) = importFx(
            """<effect id="com.alightcreative.effects.motionblur4" locallyApplied="true">
      <property name="tune" type="float" value="2.200000"/>
      <property name="usePos" type="bool" value="false"/>
      <property name="samplesMin" type="float" value="4.000000"/>
      <property name="samplesMax" type="float" value="64.000000"/>
    </effect>"""
        )
        assertEquals(220f, fx.values[0], 0.001f)         // tune above 1 is kept (def range 0..4)
        assertEquals(0f, fx.values[1], 0.001f)
        assertEquals(1f, fx.values[2], 0.001f)
        assertEquals(4f, fx.values[4], 0.001f)
        assertEquals(64f, fx.values[5], 0.001f)
    }

    @Test
    fun allFlagsOffIsSkipped() {
        val r = AlightXmlImporter.parse(
            shapeWith(
                """<effect id="com.alightcreative.effects.motionblur4" locallyApplied="true">
      <property name="tune" type="float" value="1.000000"/>
      <property name="usePos" type="bool" value="false"/>
      <property name="useScale" type="bool" value="false"/>
      <property name="useAngle" type="bool" value="false"/>
    </effect>"""
            ), 1080f
        )
        assertTrue(r.snapshot.tracks.flatMap { it.clips }[0].effects.none { it.type == ClipFxLibrary.MOTION_BLUR4 })
        assertTrue(r.warnings.any { it.contains("motionblur4 is not supported yet") })
    }

    @Test
    fun keyframedTuneRidesTheTailRaw() {
        // omniframe-onyankopon.xml's shape: tune keyframed 0.75 -> 1.5 across the span.
        val r = AlightXmlImporter.parse(
            shapeWith(
                """<effect id="com.alightcreative.effects.motionblur4" locallyApplied="true">
      <property name="tune" type="float">
        <kf t="0.000000" v="0.750000"/>
        <kf t="1.000000" v="1.500000"/>
      </property>
    </effect>"""
            ), 1080f
        )
        val clip = r.snapshot.tracks.flatMap { it.clips }[0]
        val fx = clip.effects.single { it.type == ClipFxLibrary.MOTION_BLUR4 }
        assertEquals(75f, AmFxEval.resolve(listOf(fx), clip, clip.startMs).single().values[0], 0.5f)
        assertEquals(150f, AmFxEval.resolve(listOf(fx), clip, clip.startMs + clip.durationMs).single().values[0], 0.5f)
        // And the resolved stage carries the tune RAW (1.5), not clamped to the older ids' 0..1.
        val slide: (Long) -> MotionBlurEval.Pose? = { ms -> MotionBlurEval.Pose(ms * 0.3f, 0f, 0f, 100f) }
        val atEnd = AmFxEval.resolve(listOf(fx), clip, clip.startMs + clip.durationMs)
        val ops = MotionBlurEval.resolve(atEnd, slide, clip.startMs + clip.durationMs, 33L, 1080f)
        assertEquals(ClipFxLibrary.MB4_POS, ops.single().type)
        assertEquals(1.5f, ops.single().values[2], 0.01f)
    }

    // ---- The per-frame resolver -------------------------------------------------

    private fun mb4Fx(values: List<Float> = listOf(100f, 1f, 1f, 1f, 1f, 100f)) =
        ClipFx(type = ClipFxLibrary.MOTION_BLUR4, values = values)

    @Test
    fun translationBecomesThePositionStageInPictureSpace() {
        // Sliding right at 0.3 px/ms: 9.6 px per 32 ms sample on a 1080-tall canvas.
        val slide: (Long) -> MotionBlurEval.Pose? = { ms -> MotionBlurEval.Pose(ms * 0.3f, 0f, 0f, 100f) }
        val out = MotionBlurEval.resolve(listOf(mb4Fx()), slide, 1000L, 33L, 1080f)
        assertEquals(1, out.size)
        assertEquals(ClipFxLibrary.MB4_POS, out[0].type)
        assertEquals(9.6f / 1080f, out[0].values[0], 0.0001f)   // fraction of the canvas height
        assertEquals(0f, out[0].values[1], 0.0001f)
        assertEquals(1f, out[0].values[2], 0.001f)              // tune raw
        assertEquals(1f, out[0].values[3], 0.001f)              // samplesMin
        assertEquals(100f, out[0].values[4], 0.001f)            // samplesMax
    }

    @Test
    fun positionVelocityIsUndoneByTheDrawRotation() {
        // The picture itself is turned 90 degrees clockwise, so screen-right motion is
        // picture-space DOWN in the shader's y-up frame: (0, -v).
        val turned: (Long) -> MotionBlurEval.Pose? = { ms -> MotionBlurEval.Pose(ms * 0.3f, 0f, 90f, 100f) }
        val out = MotionBlurEval.resolve(listOf(mb4Fx()), turned, 1000L, 33L, 1080f)
        val pos = out.single { it.type == ClipFxLibrary.MB4_POS }
        assertEquals(0f, pos.values[0], 0.0001f)
        assertEquals(-9.6f / 1080f, pos.values[1], 0.0001f)
        // The constant 90-degree pose has no angular velocity: no angle stage.
        assertTrue(out.none { it.type == ClipFxLibrary.MB4_ANGLE })
    }

    @Test
    fun scaleChangeBecomesTheScaleStageWithThePivot() {
        val grow: (Long) -> MotionBlurEval.Pose? = { ms -> MotionBlurEval.Pose(0f, 0f, 0f, if (ms < 1000L) 100f else 110f) }
        val out = MotionBlurEval.resolve(listOf(mb4Fx()), grow, 1000L, 33L, 1080f, 30f, 70f)
        val sc = out.single()
        assertEquals(ClipFxLibrary.MB4_SCALE, sc.type)
        assertEquals(10f / 105f, sc.values[0], 0.0001f)   // relative change per frame
        assertEquals(30f, sc.values[2], 0.001f)           // the layer's pivot, %
        assertEquals(70f, sc.values[3], 0.001f)
    }

    @Test
    fun rotationBecomesTheAngleStage() {
        val spin: (Long) -> MotionBlurEval.Pose? = { ms -> MotionBlurEval.Pose(0f, 0f, ms * 0.09f, 100f) }
        val out = MotionBlurEval.resolve(listOf(mb4Fx()), spin, 1000L, 33L, 1080f)
        val an = out.single()
        assertEquals(ClipFxLibrary.MB4_ANGLE, an.type)
        assertEquals(2.88f, an.values[0], 0.01f)          // degrees per frame over the 32 ms sample
    }

    @Test
    fun stagesComeInTheDefsGroupOrder() {
        val all: (Long) -> MotionBlurEval.Pose? = { ms -> MotionBlurEval.Pose(ms * 0.3f, 0f, ms * 0.09f, 100f + ms * 0.01f) }
        val out = MotionBlurEval.resolve(listOf(mb4Fx()), all, 1000L, 33L, 1080f)
        assertEquals(
            listOf(ClipFxLibrary.MB4_POS, ClipFxLibrary.MB4_SCALE, ClipFxLibrary.MB4_ANGLE),
            out.map { it.type }
        )
    }

    @Test
    fun stillLayerAndDisabledFlagsEmitNothing() {
        val still: (Long) -> MotionBlurEval.Pose? = { MotionBlurEval.Pose(100f, 50f, 0f, 100f) }
        assertTrue(MotionBlurEval.resolve(listOf(mb4Fx()), still, 1000L, 33L, 1080f).isEmpty())
        val slide: (Long) -> MotionBlurEval.Pose? = { ms -> MotionBlurEval.Pose(ms * 0.3f, 0f, 0f, 100f) }
        val noPos = mb4Fx(listOf(100f, 0f, 1f, 1f, 1f, 100f))
        assertTrue(MotionBlurEval.resolve(listOf(noPos), slide, 1000L, 33L, 1080f).isEmpty())
    }

    @Test
    fun stagesReachTheEngineAsNative179Family() {
        val pos = ClipFx(type = ClipFxLibrary.MB4_POS, values = listOf(0.01f, -0.02f, 1.5f, 1f, 100f))
        val arr = ClipFxLibrary.toNative(listOf(pos))!!
        assertEquals(180f, arr[0], 0.001f)
        assertEquals(0.01f, arr[1], 0.0001f)
        assertEquals(-0.02f, arr[2], 0.0001f)
        assertEquals(1.5f, arr[3], 0.001f)
        val scale = ClipFx(type = ClipFxLibrary.MB4_SCALE, values = listOf(0.05f, 1f, 30f, 70f, 1f, 100f))
        assertEquals(181f, ClipFxLibrary.toNative(listOf(scale))!![0], 0.001f)
        val angle = ClipFx(type = ClipFxLibrary.MB4_ANGLE, values = listOf(12f, 1f, 50f, 50f, 1f, 100f))
        val aa = ClipFxLibrary.toNative(listOf(angle))!!
        assertEquals(182f, aa[0], 0.001f)
        assertEquals(12f, aa[1], 0.001f)
    }
}
