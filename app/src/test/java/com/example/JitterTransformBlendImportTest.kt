package com.example

import com.example.engine.AlightXmlImporter
import com.example.model.AnimProp
import com.example.model.BlendKind
import com.example.model.BlendModes
import com.example.model.Easing
import com.example.model.LayerType
import com.example.model.valueAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Queue 15 next batch: `jitter` (a position-script bake), `transform2` (a baked
 * additive transform) and Alight's four remaining blend modes (Linear Burn,
 * Darker Color, Lighter Color, Diff — recovered defs, hunt round 3).
 *
 * The jitter pins are hand-computed from the def's script with the app's own
 * simplexNoise3 (extracted and evaluated standalone): with angle 90, freq 60,
 * mag 25, seed 0 the quantised time at 0.5 s is 0.5 and
 * snoise(0.5 x 637.729, 0, 0) = 2.95641238, so dx = 25 x 2.95641238 = 73.9103
 * and dy = 0 (cos 90). At t = 0 the noise is snoise(0, 0, 0) = -0.41219880,
 * so dx = -10.3050; at 1.0 s, snoise(637.729, 0, 0) = -0.27181317 (dx -6.7953).
 */
class JitterTransformBlendImportTest {

    private fun scene(layers: String, w: Int = 1000, h: Int = 1000): String =
        """<?xml version='1.0' encoding='UTF-8' ?><!-- test -->
<scene title="My Scene" width="$w" height="$h" bgcolor="#ff102030" totalTime="2000" fps="30">
$layers
</scene>"""

    private fun shape(fx: String, transform: String = """<location value="500,500,0" /><scale value="1,1" />""",
                      size: String = "100,100", extra: String = ""): String = scene(
        """<shape id="1" label="Box" startTime="0" endTime="2000" fillType="color" s=".rect" $extra>
  <transform>$transform</transform>
  <fillColor value="#ffffffff" />
  $fx
  <property name="size" type="vec2" value="$size" /></shape>"""
    )

    private fun onlyClip(xml: String, preview: Float = 1000f) =
        AlightXmlImporter.parse(xml, preview).snapshot.tracks.flatMap { it.clips }[0]

    // ---- jitter ----

    private fun jitterFx(
        angle: String = """<property name="angle" type="float" value="90" />""",
        freq: String = """<property name="freq" type="float" value="60" />""",
        mag: String = """<property name="mag" type="float" value="25" />""",
        seed: String = """<property name="seed" type="float" value="0" />""",
        slack: String = """<property name="slack" type="float" value="0" />""",
        z: String = """<property name="zjitter" type="float" value="0" />""",
        extra: String = ""
    ) = """<effect id="com.alightcreative.effects.jitter" locallyApplied="false" $extra>$angle$freq$mag$seed$slack$z</effect>"""

    @Test
    fun jitterBakesSteppedPositionFromTheScript() {
        val c = onlyClip(shape(jitterFx()))
        val pos = c.keyframes[AnimProp.POSITION]!!
        assertEquals(-10.305f, pos.valueAt(0).first, 0.01f)       // 25 x snoise(0,0,0)
        assertEquals(-24.3114f, pos.valueAt(248).first, 0.01f)     // tq = 14/60: 25 x snoise(148.8034, 0, 0) = 25 x -0.97245443
        assertEquals(10.5023f, pos.valueAt(252).first, 0.01f)      // tq = 15/60: 25 x 0.42009162
        assertEquals(73.9103f, pos.valueAt(500).first, 0.01f)      // tq = 0.5 exactly
        assertEquals(73.9103f, pos.valueAt(504).first, 0.01f)      // same step: held
        assertEquals(-6.7953f, pos.valueAt(1000).first, 0.01f)     // tq = 1.0
        assertTrue(pos.all { kotlin.math.abs(it.b) < 0.01f })      // angle 90: y takes cos = 0
        assertEquals(-10.305f, c.transformX, 0.01f)
    }

    @Test
    fun jitterSeedChangesTheNoise() {
        val c = onlyClip(shape(jitterFx(seed = """<property name="seed" type="float" value="10" />""")))
        val pos = c.keyframes[AnimProp.POSITION]!!
        // snoise(318.8645, 0, 10 x 394.417) = -0.31590181 -> dx = -7.8975 at 0.5 s.
        assertEquals(-7.8975f, pos.valueAt(500).first, 0.01f)
    }

    @Test
    fun jitterSlackAddsThePerpendicularComponent() {
        val c = onlyClip(shape(jitterFx(
            angle = """<property name="angle" type="float" value="0" />""",
            slack = """<property name="slack" type="float" value="1" />"""
        )))
        val pos = c.keyframes[AnimProp.POSITION]!!
        // angle 0: main component is (0, mag x m) = (0, 73.9103); the slack component is
        // perpendicular with m2 = snoise(624.8395, 0, 2773.908) = 0.51935136 -> dx 12.9838.
        assertEquals(12.9838f, pos.valueAt(500).first, 0.01f)
        assertEquals(73.9103f, pos.valueAt(500).second, 0.01f)
    }

    @Test
    fun jitterZJitterBakesPosZ() {
        val c = onlyClip(shape(jitterFx(z = """<property name="zjitter" type="float" value="100" />""")))
        val posZ = c.keyframes[AnimProp.POS_Z]!!
        // snoise(241.386, 0, 1729.361) = 0.42747023 at t = 0; at 0.5 s the quantised
        // noise is snoise(560.2505, 0, 1729.361) = 0.24145899 -> 24.1459.
        assertEquals(42.747f, posZ.valueAt(0).first, 0.01f)
        assertEquals(24.1459f, posZ.valueAt(500).first, 0.01f)
        assertEquals(42.747f, c.transformZ, 0.01f)
    }

    @Test
    fun jitterKeyframedMagIsReadAtEachStep() {
        // The corpus's dominant form (hardtrekk): mag keyframed, freq high.
        val c = onlyClip(shape(jitterFx(
            mag = """<property name="mag" type="float"><kf t="0" v="25" /><kf t="1" v="15" /></property>"""
        )))
        val pos = c.keyframes[AnimProp.POSITION]!!
        assertEquals(-10.305f, pos.valueAt(0).first, 0.01f)        // mag 25 at t = 0
        // At 2 s: mag 15, tq = 2.0, snoise(1275.458, 0, 0) = 2.07071982 -> 31.0608.
        assertEquals(31.0608f, pos.valueAt(2000).first, 0.05f)
    }

    @Test
    fun jitterKeyframedFreqStillBakes() {
        val c = onlyClip(shape(jitterFx(
            freq = """<property name="freq" type="float"><kf t="0" v="60" e="cubicBezier 0.0 0.0 0.0 1.0" /><kf t="1" v="1" /></property>"""
        )))
        val pos = c.keyframes[AnimProp.POSITION]!!
        assertTrue(pos.size > 10)
        assertEquals(-10.305f, pos.valueAt(0).first, 0.01f)        // freq(0) = 60, tq = 0
    }

    @Test
    fun hiddenJitterDoesNotBake() {
        val c = onlyClip(shape(jitterFx(extra = """hidden="true"""")))
        assertTrue(c.keyframes[AnimProp.POSITION] == null)
    }

    // ---- transform2 ----

    private fun transform2Fx(
        posx: String = """<property name="posx" type="float" value="0" />""",
        posy: String = """<property name="posy" type="float" value="0" />""",
        posz: String = """<property name="posz" type="float" value="1" />""",
        angle: String = """<property name="angle" type="float" value="0" />""",
        xinv: Boolean = false, yinv: Boolean = false, zinv: Boolean = false, ainv: Boolean = false
    ): String {
        fun sw(name: String, on: Boolean) = """<property name="$name" type="bool" value="$on" />"""
        return """<effect id="com.alightcreative.effects.transform2" locallyApplied="false">$posx$posy$posz$angle${sw("xinv", xinv)}${sw("yinv", yinv)}${sw("zinv", zinv)}${sw("ainv", ainv)}</effect>"""
    }

    private fun groupWith(fx: String): String = scene(
        """<embedScene id="4" label="Grp" startTime="0" endTime="2000" fillType="intrinsic">
  <transform><location value="500,500,0" /><scale value="1,1" /></transform>
  <fillColor value="#ff000000" />
  $fx
<scene title="" width="1000" height="1000" totalTime="2000">
<shape id="5" label="In" startTime="0" endTime="2000" fillType="color" s=".rect"><transform><location value="500,500,0" /></transform><fillColor value="#ff00ff00" /><property name="size" type="vec2" value="50,50" /></shape>
</scene>
</embedScene>"""
    )

    @Test
    fun transform2ConstantOffsetsAddOntoTheGroupTransform() {
        val r = AlightXmlImporter.parse(groupWith(transform2Fx(
            posx = """<property name="posx" type="float" value="100" />""",
            posy = """<property name="posy" type="float" value="-50" />""",
            posz = """<property name="posz" type="float" value="1.5" />""",
            angle = """<property name="angle" type="float" value="30" />"""
        )), 1000f)
        val g = r.snapshot.tracks.flatMap { it.clips }.single { it.type == LayerType.GROUP }
        assertEquals(100f, g.transformX, 0.01f)
        assertEquals(-50f, g.transformY, 0.01f)
        assertEquals(30f, g.rotationDeg, 0.01f)
        assertEquals(150f, g.scalePercent, 0.01f)   // 100 + (1.5 - 1) x 100
        assertTrue(r.warnings.none { it.contains("transform2") })
    }

    @Test
    fun transform2KeyframedPoszResamplesTheGroupScale() {
        val r = AlightXmlImporter.parse(groupWith(transform2Fx(
            posz = """<property name="posz" type="float"><kf t="0" v="1" /><kf t="1" v="1.5" /></property>"""
        )), 1000f)
        val g = r.snapshot.tracks.flatMap { it.clips }.single { it.type == LayerType.GROUP }
        val scl = g.keyframes[AnimProp.SCALE]!!
        assertEquals(100f, scl.valueAt(0).first, 0.01f)
        assertEquals(150f, scl.valueAt(2000).first, 0.01f)
        assertTrue(scl.all { it.b == 0f })          // the uniform convention survives
        assertEquals(100f, g.scalePercent, 0.01f)
    }

    @Test
    fun transform2InvertSwitchesNegateTheirComponents() {
        // yinv is the corpus's one real switch (khanbh-op02 family); the rest are the
        // same script lines, pinned synthetically. Shape 100x100 in a 1000 scene:
        // box = 1000 x 130 / 360 = 361.111, base scale 55.3846 %, the z term at
        // posz 1.5 inverted is 2 x 100 x -0.5 / 361.111 x 100 = -27.6923.
        val c = onlyClip(shape(transform2Fx(
            posx = """<property name="posx" type="float" value="10" />""",
            posy = """<property name="posy" type="float" value="40" />""",
            posz = """<property name="posz" type="float" value="1.5" />""",
            angle = """<property name="angle" type="float" value="15" />""",
            xinv = true, yinv = true, zinv = true, ainv = true
        )))
        assertEquals(-10f, c.transformX, 0.01f)
        assertEquals(-40f, c.transformY, 0.01f)
        assertEquals(-15f, c.rotationDeg, 0.01f)
        assertEquals(27.6923f, c.scalePercent, 0.01f)
        assertEquals(0.276923f, c.shapeScaleX, 0.0001f)
        assertEquals(0.276923f, c.shapeScaleY, 0.0001f)
    }

    @Test
    fun transform2KeyframedPoszOnAShapeRunsThroughTheSize() {
        // The omniframe-loser-pf pattern: a 640x360 shape, layer scale 1, posz 1 -> 1.5.
        // Scene 1920x1080: box = 693.333; base composed scale = (184.6154, 103.8462);
        // the gain at posz 1.5 is 2 x size x 0.5 / box x 100 = (92.3077, 51.9231).
        val xml = scene(
            """<shape id="1" label="Media" startTime="0" endTime="2000" fillType="color" s=".rect">
  <transform><location value="960,540,0" /><scale value="1,1" /></transform>
  <fillColor value="#ffffffff" />
  ${transform2Fx(posz = """<property name="posz" type="float"><kf t="0" v="1" /><kf t="1" v="1.5" /></property>""")}
  <property name="size" type="vec2" value="640,360" /></shape>""", 1920, 1080
        )
        val c = onlyClip(xml, 1920f)
        val scl = c.keyframes[AnimProp.SCALE]!!
        assertEquals(184.6154f, scl.valueAt(0).first, 0.05f)
        assertEquals(103.8462f, scl.valueAt(0).second, 0.05f)
        assertEquals(276.9231f, scl.valueAt(2000).first, 0.05f)
        assertEquals(155.7692f, scl.valueAt(2000).second, 0.05f)
        assertEquals(184.6154f, c.scalePercent, 0.05f)
        assertEquals(1.846154f, c.shapeScaleX, 0.001f)
        assertEquals(1.038462f, c.shapeScaleY, 0.001f)
    }

    @Test
    fun transform2ConstantPoszOverKeyframedSizeKeepsTheBaseKeys() {
        // The shape's own size animates 100 -> 200 (composed keys); a constant
        // transform2 posz of 2 (ds = 1) shifts each composed key by the term at its
        // own time: +55.3846 at t = 0 (size 100), +110.7692 at t = 2000 (size 200).
        val fx = transform2Fx(posz = """<property name="posz" type="float" value="2" />""")
        val withFx = shape(fx).replace(
            """<property name="size" type="vec2" value="100,100" />""",
            """<property name="size" type="vec2"><kf t="0" v="100,100" /><kf t="1" v="200,200" /></property>"""
        )
        val plain = shape("").replace(
            """<property name="size" type="vec2" value="100,100" />""",
            """<property name="size" type="vec2"><kf t="0" v="100,100" /><kf t="1" v="200,200" /></property>"""
        )
        val c = onlyClip(withFx)
        val base = onlyClip(plain)
        val scl = c.keyframes[AnimProp.SCALE]!!
        val baseScl = base.keyframes[AnimProp.SCALE]!!
        assertEquals(baseScl.size, scl.size)
        assertEquals(baseScl.map { it.timeMs }, scl.map { it.timeMs })
        assertEquals(baseScl.map { it.easing }, scl.map { it.easing })
        assertEquals(110.7692f, scl.valueAt(0).first, 0.05f)
        assertEquals(221.5385f, scl.valueAt(2000).first, 0.05f)
    }

    @Test
    fun transform2KeyframedPosyAddsAtTheKeyTimes() {
        val c = onlyClip(shape(transform2Fx(
            posy = """<property name="posy" type="float"><kf t="0" v="0" /><kf t="0.5" v="143" /><kf t="1" v="0" /></property>"""
        )))
        val pos = c.keyframes[AnimProp.POSITION]!!
        assertEquals(0f, pos.valueAt(0).second, 0.01f)
        assertEquals(143f, pos.valueAt(1000).second, 0.01f)
        assertEquals(0f, pos.valueAt(2000).second, 0.01f)
        assertTrue(pos.all { it.a == 0f })
    }

    @Test
    fun transform2DefaultsAreANoOp() {
        val r = AlightXmlImporter.parse(shape(transform2Fx()), 1000f)
        val c = r.snapshot.tracks.flatMap { it.clips }[0]
        assertTrue(c.keyframes[AnimProp.POSITION] == null)
        assertTrue(c.keyframes[AnimProp.SCALE] == null)
        assertTrue(c.keyframes[AnimProp.ROTATION] == null)
        assertTrue(r.warnings.none { it.contains("transform2") })
    }

    // ---- blend modes ----

    private fun blendShape(blending: String) = scene(
        """<shape id="3" label="B" startTime="0" endTime="2000" fillType="color" s=".rect" blending="$blending">
  <transform><location value="500,500,0" /><scale value="1,1" /></transform>
  <fillColor value="#ff336699" />
  <property name="size" type="vec2" value="100,100" /></shape>"""
    )

    @Test
    fun alightBlendModesMapToTheNewSpecs() {
        val cases = mapOf(
            "linear-burn" to "Linear Burn", "darker-color" to "Darker Color",
            "lighter-color" to "Lighter Color", "diff" to "Diff"
        )
        for ((am, app) in cases) {
            val r = AlightXmlImporter.parse(blendShape(am), 1000f)
            val c = r.snapshot.tracks.flatMap { it.clips }.single()
            assertEquals(app, c.blendMode)
            assertTrue("no fallback warning for $am", r.warnings.none { it.contains("normal layer") })
        }
    }

    @Test
    fun blendCatalogueHasTheNewNativeShaders() {
        assertEquals(13, BlendModes.find("Linear Burn").shaderId)
        assertEquals(14, BlendModes.find("Darker Color").shaderId)
        assertEquals(24, BlendModes.find("Lighter Color").shaderId)
        assertEquals(43, BlendModes.find("Diff").shaderId)
        for (name in listOf("Linear Burn", "Darker Color", "Lighter Color", "Diff")) {
            assertEquals(BlendKind.COLOR, BlendModes.find(name).kind)
        }
    }
}
