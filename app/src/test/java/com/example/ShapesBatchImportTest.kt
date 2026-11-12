package com.example

import com.example.engine.AlightXmlImporter
import com.example.model.AnimProp
import com.example.model.LayerType
import com.example.model.PATH_OP_CLOSE
import com.example.model.PATH_OP_CUBIC
import com.example.model.PATH_OP_LINE
import com.example.model.PATH_OP_MOVE
import com.example.model.TimelineClip
import com.example.model.arcSegmentsAt
import com.example.model.hasArc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Build 137: the shapes batch + the transform pivot fold.
 *  - `.arc` (khanbh-scene-9 x2) ported from AM's own shape definition (defs-batch /
 *    shape-defs-r2), with per-frame angle evaluation like the `.pie`'s;
 *  - `com.alightcreative.arrow2` (khanbh-scene-6 x2): a free-point polygon over its
 *    serialised p1..p6 — no def for this kind exists in either recovered dump, the
 *    instances themselves are the schema;
 *  - path-stroke `end="circle-filled"` caps (khanbh-op02 x2, end-size 2.6 on a width-9
 *    stroke): the cap size rides the clip, the PATH painter draws the disc; an
 *    end-size with no cap style stays inert, as on all 132 other corpus strokes;
 *  - `.triangle` verified against its first real instance (khanbh-iphone-17-animation:
 *    exactly the def defaults build 122 ported blind);
 *  - transform `<pivot>` on shape / text / null layers (105 corpus instances, warned and
 *    ignored until now) folds into the position: pos(t) = loc + P - R.S.P per frame.
 *    The corpus's only KEYFRAMED pivot (blxze-editardd-rmk) is a single (0, 0) key,
 *    which composes exactly like no pivot and keeps the plain location handling.
 * All pins below were computed independently of the importer (the def scripts and the
 * composition formula evaluated by hand / in Python).
 */
class ShapesBatchImportTest {

    private fun scene(w: Int, h: Int, total: Long, layers: String): String =
        """<?xml version='1.0' encoding='UTF-8' ?><!-- test -->
<scene title="T" width="$w" height="$h" bgcolor="#ff102030" totalTime="$total" fps="30">
$layers
</scene>"""

    private fun clips(xml: String): Pair<AlightXmlImporter.ImportResult, List<TimelineClip>> {
        val r = AlightXmlImporter.parse(xml, 1080f)
        return r to r.snapshot.tracks.flatMap { it.clips }
    }

    private fun segOps(c: TimelineClip): List<Int> = c.pathSegments.map { it.op }

    // ---- .arc ---------------------------------------------------------------------------------

    @Test
    fun arcConstantNearFullCircle() {
        // khanbh-scene-9 "Arc 1 Copy", verbatim: start 91, end 90 — the def's wrap rule
        // sweeps the LONG way (359 degrees), in four Riskus cubics of <= 90 degrees.
        val xml = scene(
            1920, 1080, 7199,
            """<shape id="10156871" label="Arc 1 Copy" startTime="0" endTime="7199" fillType="none" s=".arc">
  <transform><location value="1559.000000,535.500000,0.000000" /><scale value="1.928885,1.928885" /></transform>
  <fillColor value="#ffb9eede" />
  <path-stroke direction="centered" end-size="1.500000"><color value="#ff00ffff" /><size value="70.500000" /></path-stroke>
  <property name="startAngle" type="float" value="91.000000" />
  <property name="endAngle" type="float" value="90.000000" />
  <property name="radius" type="float" value="142.000000" />
  <property name="closed" type="bool" value="false" />
</shape>"""
        )
        val (r, cs) = clips(xml)
        assertTrue(r.warnings.none { it.contains("\".arc\"") || it.contains("shape kind") })
        val c = cs.single()
        assertTrue(c.hasArc)
        assertEquals(142f, c.arcRadius, 0.001f)
        assertEquals(false, c.arcClosed)
        assertEquals(listOf(PATH_OP_MOVE, PATH_OP_CUBIC, PATH_OP_CUBIC, PATH_OP_CUBIC, PATH_OP_CUBIC), segOps(c))
        // First point: the prepared low angle is 269 degrees in the script's frame.
        assertEquals(-0.01745f, c.pathSegments[0].pts[0], 0.001f)
        assertEquals(-0.99985f, c.pathSegments[0].pts[1], 0.001f)
        // Constant angles: the per-frame rebuild is the stored outline.
        assertEquals(segOps(c), c.arcSegmentsAt(0L).map { it.op })
        // Stroke: 130 * 70.5 / (2 * 142) — the layer scale cancels, as for a pie.
        assertEquals(32.29f, c.strokeWidth, 0.05f)
        // end-size 1.5 with NO end style: inert, no cap.
        assertEquals(0f, c.strokeCapEndSize, 0.0001f)
    }

    @Test
    fun arcKeyframedStartAngle() {
        // khanbh-scene-9 "Arc 1", verbatim: startAngle animates 450 -> 109 -> ... -> 110
        // against a constant endAngle of 90. At 450 the authored difference is a full turn,
        // which the wrap rule closes completely; at 109 the sweep is 341 degrees.
        val xml = scene(
            1920, 1080, 7199,
            """<shape id="10156870" label="Arc 1" startTime="0" endTime="7199" fillType="none" s=".arc">
  <transform><location value="1559.000000,535.500000,0.000000" /><scale value="1.928885,1.928885" /></transform>
  <fillColor value="#ffb9eede" />
  <path-stroke direction="centered" end-size="1.500000"><color value="#ffffffff" /><size value="74.500000" /></path-stroke>
  <property name="startAngle" type="float">
    <kf t="0.002226" v="450.000000" />
    <kf t="0.141128" v="109.000000" e="cubicBezier 1.0 0.0 0.0 1.0" />
    <kf t="0.219889" v="109.000000" />
    <kf t="0.331014" v="305.000000" />
    <kf t="0.423668" v="305.000000" />
    <kf t="0.525486" v="336.000000" e="cubicBezier 0.42 0.0 0.58 1.0" />
    <kf t="0.594941" v="336.000000" />
    <kf t="0.682875" v="371.000000" e="cubicBezier 0.42 0.0 0.58 1.0" />
    <kf t="0.743158" v="371.000000" />
    <kf t="0.835670" v="110.000000" e="cubicBezier 0.42 0.0 0.58 1.0" />
  </property>
  <property name="endAngle" type="float" value="90.000000" />
  <property name="radius" type="float" value="142.000000" />
  <property name="closed" type="bool" value="false" />
</shape>"""
        )
        val (r, cs) = clips(xml)
        assertTrue(r.warnings.none { it.contains("shape kind") })
        val c = cs.single()
        assertTrue(c.hasArc)
        assertEquals(10, c.arcStartKeys.size)
        assertEquals(450f, c.arcStartKeys.first().a, 0.001f)
        // t = 0 evaluates to the first key (450): the arc is closed, nothing is drawn.
        assertTrue(c.arcSegmentsAt(0L).isEmpty())
        // At the 109-degree key (t = 0.141128 * 7199 = 1016 ms): 341 degrees of sweep.
        val at109 = c.arcSegmentsAt(1016L)
        assertEquals(5, at109.size)
        assertEquals(PATH_OP_MOVE, at109[0].op)
        // The stored outline is the most open instant (the same 341-degree arc).
        assertEquals(5, c.pathSegments.size)
    }

    // ---- arrow2 -------------------------------------------------------------------------------

    @Test
    fun arrow2FreePointPolygon() {
        // khanbh-scene-6 "Arrow 2 1", verbatim points: the block-arrow outline.
        val xml = scene(
            1920, 1080, 6699,
            """<shape id="4998" label="Arrow 2 1" startTime="0" endTime="6699" fillType="color" s="com.alightcreative.arrow2">
  <transform><location value="616.829346,934.033447,0.000000" /><scale value="0.259091,0.259091" /><rotation value="-90.000000" /></transform>
  <fillColor value="#ffbebebe" />
  <property name="p1" type="vec2" value="-330.000000,-160.000000" />
  <property name="p2" type="vec2" value="0.000000,-390.000000" />
  <property name="p3" type="vec2" value="330.000000,-160.000000" />
  <property name="p4" type="vec2" value="330.000000,67.000000" />
  <property name="p5" type="vec2" value="0.000000,-103.000000" />
  <property name="p6" type="vec2" value="-330.000000,67.000000" />
  <property name="closed" type="bool" value="true" />
</shape>"""
        )
        val (r, cs) = clips(xml)
        assertTrue(r.warnings.none { it.contains("arrow2") })
        val c = cs.single()
        assertEquals(-90f, c.rotationDeg, 0.001f)
        // Box: x [-330, 330], y [-390, 67] -> centre (0, -161.5), half 330.
        assertEquals(
            listOf(PATH_OP_MOVE, PATH_OP_LINE, PATH_OP_LINE, PATH_OP_LINE, PATH_OP_LINE, PATH_OP_LINE, PATH_OP_CLOSE),
            segOps(c)
        )
        val expect = listOf(
            -1f to 0.004545f, 0f to -0.692424f, 1f to 0.004545f,
            1f to 0.692424f, 0f to 0.177273f, -1f to 0.692424f
        )
        for (i in expect.indices) {
            assertEquals("x$i", expect[i].first, c.pathSegments[i].pts[0], 0.001f)
            assertEquals("y$i", expect[i].second, c.pathSegments[i].pts[1], 0.001f)
        }
    }

    // ---- .triangle, real instance --------------------------------------------------------------

    @Test
    fun triangleMatchesItsRealInstance() {
        // khanbh-iphone-17-animation "Triangle 1" carries exactly the def defaults build 122
        // ported blind (p1 -100,100 / p2 0,-100 / p3 100,100, closed) — and a bare triangle
        // (all defaults) must produce the identical outline.
        val props = """<property name="p1" type="vec2" value="-100.000000,100.000000" />
  <property name="p2" type="vec2" value="0.000000,-100.000000" />
  <property name="p3" type="vec2" value="100.000000,100.000000" />
  <property name="closed" type="bool" value="true" />"""
        val xml = scene(
            1080, 1920, 3999,
            """<shape id="102342955" label="Triangle 1" startTime="0" endTime="3999" fillType="color" s=".triangle">
  <transform><location value="300,900,0" /></transform>
  <fillColor value="#ff6f9fbb" />
  $props
</shape>
<shape id="2" label="Bare" startTime="0" endTime="3999" fillType="color" s=".triangle">
  <transform><location value="700,900,0" /></transform>
  <fillColor value="#ff6f9fbb" />
</shape>"""
        )
        val (_, cs) = clips(xml)
        assertEquals(2, cs.size)
        for (c in cs) {
            assertEquals(listOf(PATH_OP_MOVE, PATH_OP_LINE, PATH_OP_LINE, PATH_OP_CLOSE), segOps(c))
            val expect = listOf(-1f to 1f, 0f to -1f, 1f to 1f)
            for (i in expect.indices) {
                assertEquals("x$i", expect[i].first, c.pathSegments[i].pts[0], 0.001f)
                assertEquals("y$i", expect[i].second, c.pathSegments[i].pts[1], 0.001f)
            }
        }
    }

    // ---- end caps ------------------------------------------------------------------------------

    @Test
    fun circleFilledEndCap() {
        // khanbh-op02 "Shape 1" / "Shape 1 Copy" strokes, verbatim: end="circle-filled",
        // end-size 2.6, width 9, red, on the up-and-back line path. Controls: the same
        // end-size with no style (inert), a start cap, and an unseen style (warned, no cap).
        fun shape(id: Int, label: String, strokeAttrs: String) =
            """<shape id="$id" label="$label" startTime="0" endTime="4000" fillType="none" s="">
  <transform><location value="960.000000,546.037109,0.000000" /></transform>
  <fillColor value="#ffd5488e" />
  <path-stroke direction="centered" $strokeAttrs><color value="#ffff0000" /><size value="9.000000" /></path-stroke>
  <path d="M -430.36108 93.96289L -430.36108 -106.45117L -430.36108 93.96289" />
</shape>"""
        val xml = scene(
            1920, 1080, 4000,
            shape(102342034, "Capped", """end="circle-filled" end-size="2.600000"""") + "\n" +
                shape(2, "Inert", """end-size="2.600000"""") + "\n" +
                shape(3, "StartCap", """start="circle-filled" start-size="2.000000"""") + "\n" +
                shape(4, "Unknown", """end="arrow" end-size="2.600000"""")
        )
        val (r, cs) = clips(xml)
        val capped = cs.single { it.name == "Capped" }
        assertEquals(2.6f, capped.strokeCapEndSize, 0.0001f)
        assertEquals(0f, capped.strokeCapStartSize, 0.0001f)
        assertEquals(0xFFFF0000L, capped.strokeColor)
        // The path's box: height 200.414, half 100.207 -> stroke 130 * 9 / 200.414.
        assertEquals(5.838f, capped.strokeWidth, 0.01f)
        // Normalised outline: the vertical line x = 0, from y = 1 up to y = -1 and back.
        assertEquals(listOf(PATH_OP_MOVE, PATH_OP_LINE, PATH_OP_LINE), segOps(capped))
        assertEquals(0f, capped.pathSegments[0].pts[0], 0.001f)
        assertEquals(1f, capped.pathSegments[0].pts[1], 0.001f)
        assertEquals(-1f, capped.pathSegments[1].pts[1], 0.001f)
        val inert = cs.single { it.name == "Inert" }
        assertEquals(0f, inert.strokeCapEndSize, 0.0001f)
        val startCap = cs.single { it.name == "StartCap" }
        assertEquals(2.0f, startCap.strokeCapStartSize, 0.0001f)
        assertEquals(0f, startCap.strokeCapEndSize, 0.0001f)
        val unknown = cs.single { it.name == "Unknown" }
        assertEquals(0f, unknown.strokeCapEndSize, 0.0001f)
        assertTrue(r.warnings.any { it.contains("cap \"arrow\"") })
    }

    // ---- pivot fold ----------------------------------------------------------------------------

    @Test
    fun pivotFoldOnAPathShape() {
        // khanbh-op02 "Shape 1 Copy", verbatim transform: location slides 960 -> 1820.72,
        // pivot (-430.361, -20.486), rotation -180. The anchor is loc + P + R.(c - P) with
        // c the path box centre (-430.361, -6.244): at t = 0, (529.639, 539.793).
        val xml = scene(
            1920, 1080, 4000,
            """<shape id="102342035" label="Shape 1 Copy" startTime="0" endTime="4000" fillType="none" s="">
  <transform>
    <location><kf t="0.000000" v="960.000000,574.521484,0.000000" /><kf t="0.614855" v="1820.722168,574.521484,0.000000" /></location>
    <pivot value="-430.361084,-20.486328" />
    <rotation value="-180.000000" />
  </transform>
  <fillColor value="#ffd5488e" />
  <path-stroke direction="centered" end="circle-filled" end-size="2.600000"><color value="#ffff0000" /><size value="9.000000" /></path-stroke>
  <path d="M -430.36108 93.96289L -430.36108 -106.45117L -430.36108 93.96289" />
</shape>"""
        )
        val (r, cs) = clips(xml)
        assertTrue(r.warnings.none { it.contains("pivot") })
        val c = cs.single()
        assertEquals(-242.078f, c.transformX, 0.05f)
        assertEquals(-0.116f, c.transformY, 0.05f)
        val keys = c.keyframes.getValue(AnimProp.POSITION)
        assertEquals(2, keys.size)
        assertEquals(2459L, keys[1].timeMs)
        assertEquals(242.078f, keys[1].a, 0.05f)
        assertEquals(-0.116f, keys[1].b, 0.05f)
        assertEquals(2.6f, c.strokeCapEndSize, 0.0001f)
    }

    @Test
    fun pivotFoldOnARectWithKeyframedScale() {
        // The robo packs' pattern: a .rect at the scene centre, pivot far off-centre,
        // scale keyframed 1 -> 2.436111 -> 1. At scale 1 the fold is the identity; at the
        // mid key the position is loc + P - S.P = (632.577, 1142.755).
        val xml = scene(
            1440, 1440, 2000,
            """<shape id="102411083" label="Photo" startTime="0" endTime="2000" fillType="color" s=".rect">
  <transform>
    <location value="720.000000,720.000000,0.000000" />
    <pivot value="60.875000,-294.375000" />
    <scale><kf t="0.000000" v="1.000000,1.000000" /><kf t="0.500000" v="2.436111,2.436111" /><kf t="1.000000" v="1.000000,1.000000" /></scale>
  </transform>
  <fillColor value="#ffffffff" />
  <property name="size" type="vec2" value="100,100" />
</shape>"""
        )
        val (r, cs) = clips(xml)
        assertTrue(r.warnings.none { it.contains("pivot") })
        val c = cs.single()
        assertEquals(0f, c.transformX, 0.01f)
        assertEquals(0f, c.transformY, 0.01f)
        val keys = c.keyframes.getValue(AnimProp.POSITION)
        assertEquals(3, keys.size)
        assertEquals(1000L, keys[1].timeMs)
        assertEquals(-65.567f, keys[1].a, 0.3f)
        assertEquals(317.066f, keys[1].b, 0.3f)
    }

    @Test
    fun pivotFoldOnANullAnchor() {
        // robo-full-cr7-edit "Null 1", verbatim: loc (699, 720), pivot (82.375, -337.375),
        // scale keyframed 3.12 -> 1. The stored pose at t = 0 is loc + P - 3.12 * P.
        val xml = scene(
            1440, 1440, 2000,
            """<nullobj id="102411059" label="Null 1" startTime="0" endTime="2000">
  <transform>
    <location value="699.000000,720.000000,0.000000" />
    <pivot value="82.375000,-337.375000" />
    <scale><kf t="0.000000" v="3.120000,3.120000" /><kf t="1.000000" v="1.000000,1.000000" /></scale>
  </transform>
</nullobj>"""
        )
        val (r, cs) = clips(xml)
        assertTrue(r.warnings.none { it.contains("pivot") })
        val c = cs.single()
        assertEquals(LayerType.NULL, c.type)
        assertEquals(-146.726f, c.transformX, 0.3f)
        assertEquals(536.426f, c.transformY, 0.3f)
    }

    @Test
    fun pivotFoldOnTextWithMirroredScale() {
        // khanbh-op02 text 102343147, verbatim transform: pivot (0.161, 41.112), rotation
        // -25.258, scale keyframed from (0.018182, -0.018182). At t = 0 the fold gives
        // (1536.070, 364.777) in scene units.
        val xml = scene(
            1920, 1080, 4000,
            """<text id="102343147" label="Word" startTime="0" endTime="4000" font="Inter" size="48" fillColor="#ffffffff" align="center">
  <transform>
    <location value="1535.593262,322.987793,-125.260620" />
    <pivot value="0.160594,41.111897" />
    <rotation value="-25.257965" />
    <scale><kf t="0.421955" v="-2.830303,2.830303" /><kf t="0.007290" v="0.018182,-0.018182" /></scale>
  </transform>
  <content>Word</content>
</text>"""
        )
        val (r, cs) = clips(xml)
        assertTrue(r.warnings.none { it.contains("pivot") })
        val c = cs.single()
        assertEquals(324.039f, c.transformX, 0.3f)
        assertEquals(-98.563f, c.transformY, 0.3f)
    }

    @Test
    fun zeroKeyframedPivotIsConsumedAndChangesNothing() {
        // blxze-editardd-rmk shape 10186720, verbatim transform: the corpus's only keyframed
        // pivot is a single (0, 0) key — consumed (no warning), and the position keeps the
        // plain location handling byte-for-byte (its own key times and values).
        val xml = scene(
            1440, 1440, 3000,
            """<shape id="10186720" label="Photo" startTime="0" endTime="3000" fillType="color" s=".rect">
  <transform>
    <location>
      <kf t="0.063670" v="728.593750,209.960938,0.000000" />
      <kf t="0.310861" v="720.000000,900.000000,0.000000" />
      <kf t="0.561798" v="720.000000,900.000000,0.000000" />
      <kf t="0.685393" v="603.419128,797.644592,0.000000" />
    </location>
    <scale><kf t="0.438202" v="1.000000,1.000000" /><kf t="1.000000" v="1.352778,1.352778" /></scale>
    <rotation><kf t="0.438202" v="0.000000" /><kf t="1.000000" v="-21.951233" /></rotation>
    <pivot><kf t="0.310861" v="0.000000,0.000000" /></pivot>
  </transform>
  <fillColor value="#ffffffff" />
  <property name="size" type="vec2" value="100,100" />
</shape>"""
        )
        val (r, cs) = clips(xml)
        assertTrue(r.warnings.none { it.contains("pivot") })
        val c = cs.single()
        val keys = c.keyframes.getValue(AnimProp.POSITION)
        assertEquals(listOf(191L, 933L, 1685L, 2056L), keys.map { it.timeMs })
        assertEquals(6.445f, keys[0].a, 0.01f)
        assertEquals(-382.529f, keys[0].b, 0.01f)
        assertEquals(6.445f, c.transformX, 0.01f)
        assertEquals(-382.529f, c.transformY, 0.01f)
    }
}
