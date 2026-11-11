package com.example

import com.example.engine.AlightXmlImporter
import com.example.engine.ParentRig
import com.example.model.AnimProp
import com.example.model.LayerType
import com.example.model.TimelineClip
import com.example.model.renderAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Alight Motion layer parenting (`parent` attributes + `<nullobj>` anchors). The composition the
 * importer must reproduce is Alight's absolute one — world = parent ∘ local, the child's stored
 * location being the offset from the parent's anchor — expressed through the app's ParentRig with a
 * link whose reference pose is the scene frame's origin (see AlightXmlImporter.resolveParentLinks).
 * Expected positions below are computed by hand from the XML values, in stored units (preview px =
 * scene px here, positions measured from the scene centre).
 */
class ParentingImportTest {

    private fun scene(layers: String, w: Int = 1080, h: Int = 1920, total: Long = 2000) =
        """<?xml version='1.0' encoding='UTF-8' ?><!-- test -->
<scene title="P" width="$w" height="$h" bgcolor="#ff102030" totalTime="$total" fps="30">
$layers
</scene>"""

    private fun nullEl(id: String, transform: String, extra: String = "", parent: String = "") =
        """<nullobj id="$id" label="Null $id" startTime="0" endTime="2000" mediaFillMode="stretch" type="perspective"${if (parent.isEmpty()) "" else " parent=\"$parent\""}><transform>$transform</transform>$extra</nullobj>"""

    private fun rectEl(id: String, label: String, transform: String, parent: String = "", start: Long = 0, end: Long = 2000) =
        """<shape id="$id" label="$label" startTime="$start" endTime="$end" fillType="color"${if (parent.isEmpty()) "" else " parent=\"$parent\""} mediaFillMode="stretch" s=".rect"><transform>$transform</transform><fillColor value="#ff00ff00" /><property name="size" type="vec2" value="100.000000,100.000000" /></shape>"""

    private fun clipsOf(xml: String, w: Float) =
        AlightXmlImporter.parse(xml, w).snapshot.tracks.flatMap { it.clips }

    private fun rig(clips: List<TimelineClip>) = ParentRig(clips) { c, t -> c.renderAt(t) }

    @Test
    fun nullobjImportsAsNullClip() {
        val r = AlightXmlImporter.parse(scene(nullEl("7", """<location value="700.000000,800.000000,0.000000" /><scale value="1.500000,1.500000" />""")), 1080f)
        val c = r.snapshot.tracks.flatMap { it.clips }.single()
        assertEquals(LayerType.NULL, c.type)
        assertEquals(160f, c.transformX, 0.01f)   // 700 - 540 (scene centre)
        assertEquals(-160f, c.transformY, 0.01f)  // 800 - 960
        assertEquals(150f, c.scalePercent, 0.01f)
        assertTrue(r.warnings.none { it.contains("<nullobj>") })
    }

    @Test
    fun childFollowsStaticNull() {
        val xml = scene(
            nullEl("1", """<location value="540.000000,960.000000,0.000000" /><scale value="2.000000,2.000000" />""") +
                rectEl("2", "Kid", """<location value="100.000000,50.000000,0.000000" /><scale value="1.000000,1.000000" />""", parent = "1")
        )
        val clips = clipsOf(xml, 1080f)
        val nullClip = clips.first { it.type == LayerType.NULL }
        val kid = clips.first { it.name == "Kid" }
        // The child's stored tracks stay the naive reading of its local values; only the link is added.
        assertEquals(-440f, kid.transformX, 0.01f)  // 100 - 540
        assertEquals(-910f, kid.transformY, 0.01f)  // 50 - 960
        assertNotNull(kid.parent)
        assertEquals(nullClip.id, kid.parent!!.id)
        assertEquals(-540f, kid.parent!!.refX, 0.01f)
        assertEquals(-960f, kid.parent!!.refY, 0.01f)
        assertEquals(100f, kid.parent!!.refScale, 0.01f)
        assertEquals(0f, kid.parent!!.refRot, 0.01f)
        // World: null at the centre with scale 2 -> the child lands at 2 x its local offset, and its
        // scale grows by the null's factor (a shape's own scalePercent is size-normalised, so the
        // parent's contribution is asserted as the ratio).
        val adj = rig(clips).adjusted(kid, 0L)
        assertEquals(200f, adj.transformX, 0.01f)
        assertEquals(100f, adj.transformY, 0.01f)
        assertEquals(2f, adj.scalePercent / kid.renderAt(0L).scalePercent, 0.01f)
    }

    @Test
    fun childFollowsKeyframedNull() {
        val xml = scene(
            nullEl("1", """<location><kf t="0.000000" v="540.000000,960.000000,0.000000" /><kf t="1.000000" v="740.000000,960.000000,0.000000" /></location>""") +
                rectEl("2", "Kid", """<location value="0.000000,0.000000,0.000000" />""", parent = "1")
        )
        val clips = clipsOf(xml, 1080f)
        val kid = clips.first { it.name == "Kid" }
        val r = rig(clips)
        assertEquals(0f, r.adjusted(kid, 0L).transformX, 0.01f)
        assertEquals(100f, r.adjusted(kid, 1000L).transformX, 0.5f)   // linear midpoint
        assertEquals(200f, r.adjusted(kid, 2000L).transformX, 0.01f)
        assertEquals(0f, r.adjusted(kid, 1000L).transformY, 0.01f)
    }

    @Test
    fun rotatingNullSwingsChildAroundIt() {
        val xml = scene(
            nullEl("1", """<location value="540.000000,960.000000,0.000000" /><rotation><kf t="0.000000" v="0.000000" /><kf t="1.000000" v="90.000000" /></rotation>""") +
                rectEl("2", "Kid", """<location value="100.000000,0.000000,0.000000" />""", parent = "1")
        )
        val clips = clipsOf(xml, 1080f)
        val kid = clips.first { it.name == "Kid" }
        val r = rig(clips)
        val mid = r.adjusted(kid, 1000L)   // 45 degrees, clockwise on screen (y down)
        assertEquals(70.71f, mid.transformX, 0.5f)
        assertEquals(70.71f, mid.transformY, 0.5f)
        assertEquals(45f, mid.rotationDeg, 0.5f)
        val end = r.adjusted(kid, 2000L)
        assertEquals(0f, end.transformX, 0.5f)
        assertEquals(100f, end.transformY, 0.5f)
        assertEquals(90f, end.rotationDeg, 0.5f)
    }

    @Test
    fun threeNullChainComposes() {
        val xml = scene(
            nullEl("3", """<location value="540.000000,960.000000,0.000000" />""") +
                nullEl("2", """<location value="10.000000,0.000000,0.000000" />""", parent = "3") +
                nullEl("1", """<location value="-5.000000,0.000000,0.000000" />""", parent = "2") +
                rectEl("4", "Kid", """<location value="0.000000,100.000000,0.000000" />""", parent = "1")
        )
        val clips = clipsOf(xml, 1080f)
        val byName = clips.associateBy { it.name }
        assertEquals(byName["Null 3"]!!.id, byName["Null 2"]!!.parent!!.id)
        assertEquals(byName["Null 2"]!!.id, byName["Null 1"]!!.parent!!.id)
        assertEquals(byName["Null 1"]!!.id, byName["Kid"]!!.parent!!.id)
        // World: centre + (10,0) + (-5,0) + (0,100) = (5,100) from the centre.
        val adj = rig(clips).adjusted(byName["Kid"]!!, 0L)
        assertEquals(5f, adj.transformX, 0.01f)
        assertEquals(100f, adj.transformY, 0.01f)
    }

    @Test
    fun embedParentedToNullCarriesItsChildren() {
        val inner = rectEl("9", "Inner", """<location value="1060.000000,540.000000,0.000000" />""")
        val embed = """<embedScene id="5" label="Group 1" startTime="0" endTime="2000" fillType="intrinsic" mediaFillMode="stretch" parent="1"><transform><location value="0.000000,0.000000,0.000000" /><scale value="0.500000,0.500000" /></transform><scene title="" width="1920" height="1080" totalTime="2000" fps="30">$inner</scene></embedScene>"""
        val xml = scene(
            nullEl("1", """<location value="960.000000,540.000000,0.000000" /><scale value="0.780000,0.780000" />""") + embed,
            w = 1920, h = 1080
        )
        val clips = clipsOf(xml, 1920f)
        val nullClip = clips.first { it.type == LayerType.NULL }
        val group = clips.first { it.type == LayerType.GROUP }
        val innerClip = clips.first { it.name == "Inner" }
        assertEquals(nullClip.id, group.parent!!.id)       // the group itself follows the null
        assertEquals(group.id, innerClip.parent!!.id)      // the child keeps its group link
        // World: the group sits on the null (scale 0.78 x 0.5 = 0.39); the child's +100 px offset scales along.
        val adj = rig(clips).adjusted(innerClip, 0L)
        assertEquals(39f, adj.transformX, 0.5f)
        assertEquals(0f, adj.transformY, 0.5f)
        assertEquals(0.39f, adj.scalePercent / innerClip.renderAt(0L).scalePercent, 0.01f)
    }

    @Test
    fun oscillateOnNullBakesIntoItsTracksAndChildrenFollow() {
        val fx = """<effect id="com.alightcreative.effects.oscillate3" locallyApplied="true"><property name="direction" type="int" value="0" /><property name="angle" type="float" value="90.000000" /><property name="freq" type="float" value="1.000000" /><property name="mag" type="float" value="50.000000" /><property name="type" type="int" value="0" /><property name="phase" type="float" value="0.000000" /></effect>"""
        val xml = scene(
            nullEl("1", """<location value="540.000000,960.000000,0.000000" />""", extra = fx) +
                rectEl("2", "Kid", """<location value="0.000000,0.000000,0.000000" />""", parent = "1")
        )
        val clips = clipsOf(xml, 1080f)
        val nullClip = clips.first { it.type == LayerType.NULL }
        val posKeys = nullClip.keyframes[AnimProp.POSITION]
        assertNotNull(posKeys)
        assertTrue(posKeys!!.map { it.a to it.b }.distinct().size >= 2)   // the shake was baked, not dropped
        val kid = clips.first { it.name == "Kid" }
        val r = rig(clips)
        val at0 = r.adjusted(kid, 0L)
        assertEquals(0f, at0.transformX, 1f)
        assertEquals(0f, at0.transformY, 1f)   // sin(phase 0) = 0 displacement
        var moved = 0f
        for (t in listOf(125L, 250L, 375L, 500L, 750L, 1000L)) {
            val a = r.adjusted(kid, t)
            moved = maxOf(moved, hypot(a.transformX - at0.transformX, a.transformY - at0.transformY))
        }
        assertTrue("the child rides the null's shake (moved $moved px)", moved > 5f)
    }

    @Test
    fun missingParentWarnsAndKeepsOwnMotion() {
        val r = AlightXmlImporter.parse(
            scene(rectEl("2", "Kid", """<location value="540.000000,960.000000,0.000000" />""", parent = "424242")), 1080f
        )
        val kid = r.snapshot.tracks.flatMap { it.clips }.first { it.name == "Kid" }
        assertNull(kid.parent)
        assertTrue(r.warnings.any { it.contains("was not imported") })
    }

    @Test
    fun groupParentIsParkedWithWarning() {
        val embed = """<embedScene id="5" label="Group 1" startTime="0" endTime="2000" fillType="intrinsic" mediaFillMode="stretch"><transform><location value="540.000000,960.000000,0.000000" /></transform><scene title="" width="1080" height="1920" totalTime="2000" fps="30"></scene></embedScene>"""
        val r = AlightXmlImporter.parse(
            scene(embed + rectEl("2", "Kid", """<location value="540.000000,960.000000,0.000000" />""", parent = "5")), 1080f
        )
        val kid = r.snapshot.tracks.flatMap { it.clips }.first { it.name == "Kid" }
        assertNull(kid.parent)
        assertTrue(r.warnings.any { it.contains("parented to a group") })
    }

    // ---- verbatim corpus instances ---------------------------------------------------------------------------

    /** dys-prst-126, verbatim: the null "Nol 1" (205147372) and one of its 37 children (205147370). */
    private val dysNull = """<nullobj id="205147372" label="Nol 1" startTime="0" endTime="18765" mediaFillMode="fill" type="perspective"><transform><location><kf t="0.185611" v="540.171814,960.369141,0.000000" e="elastic 0.41044408 1.0 0.0 0.62906396" /><kf t="0.135891" v="540.171814,960.369141,0.000000" /><kf t="0.160725" v="540.171814,1059.617676,0.000000" e="reverse elastic 0.41044408 1.0 0.0 0.62906396" /><kf t="0.341913" v="540.171814,960.369141,0.000000" /><kf t="0.395204" v="540.171814,960.369141,0.000000" e="elastic 0.41044408 1.0 0.0 0.62906396" /><kf t="0.368559" v="540.171814,1059.617676,0.000000" e="reverse elastic 0.41044408 1.0 0.0 0.62906396" /><kf t="0.553318" v="540.171814,960.369141,0.000000" /><kf t="0.604850" v="540.171814,960.369141,0.000000" e="elastic 0.41044408 1.0 0.0 0.62906396" /><kf t="0.578204" v="540.171814,1059.617676,0.000000" e="reverse elastic 0.41044408 1.0 0.0 0.62906396" /><kf t="0.762909" v="540.171814,960.369141,0.000000" /><kf t="0.814442" v="540.171814,960.369141,0.000000" e="elastic 0.41044408 1.0 0.0 0.62906396" /><kf t="0.789555" v="540.171814,1059.000000,0.000000" e="reverse elastic 0.41044408 1.0 0.0 0.62906396" /></location><scale><kf t="0.000427" v="1.000000,1.000000" /><kf t="0.169624" v="1.000000,1.000000" e="cubicBezier 0.42 0.0 0.58 1.0" /><kf t="0.341913" v="1.000000,1.000000" e="cubicBezier 0.42 0.0 0.58 1.0" /><kf t="0.377458" v="1.000000,1.000000" /><kf t="0.272635" v="1.280000,1.280000" /><kf t="0.080789" v="1.280000,1.280000" e="cubicBezier 0.42 0.0 0.5002854 1.0" /><kf t="0.237144" v="1.280000,1.280000" e="cubicBezier 0.42 0.0 0.58 1.0" /><kf t="0.448495" v="1.280000,1.280000" e="cubicBezier 0.42 0.0 0.58 1.0" /><kf t="0.480469" v="1.280000,1.280000" /><kf t="0.553317" v="1.000000,1.000000" e="cubicBezier 0.42 0.0 0.58 1.0" /><kf t="0.585292" v="1.000000,1.000000" /><kf t="0.658140" v="1.280000,1.280000" e="cubicBezier 0.42 0.0 0.58 1.0" /><kf t="0.691873" v="1.280000,1.280000" /><kf t="0.762910" v="1.000000,1.000000" e="cubicBezier 0.42 0.0 0.58 1.0" /><kf t="0.794884" v="1.000000,1.000000" /><kf t="0.867732" v="1.280000,1.280000" e="cubicBezier 0.42 0.0 0.58 1.0" /><kf t="0.901465" v="0.980000,0.980000" e="reverse elastic 0.5 1.0 0.0 1.0" /><kf t="0.999201" v="0.980000,0.980000" e="reverse elastic 0.5 1.0 0.0 1.0" /><kf t="0.134079" v="1.000000,1.000000" e="cubicBezier 0.42 0.0 0.58 1.0" /><kf t="0.947668" v="1.280000,1.280000" e="elastic 0.5 1.0 0.0 1.0" /></scale></transform><effect id="com.alightcreative.effects.oscillate3" locallyApplied="false"><property name="direction" type="int" value="0" /><property name="angle" type="float"><kf t="0.237144" v="45.000000" /><kf t="0.290434" v="45.000000" /><kf t="0.448495" v="45.000000" /><kf t="0.500027" v="45.000000" /><kf t="0.473381" v="270.000000" /><kf t="0.658140" v="45.000000" /><kf t="0.709619" v="45.000000" /><kf t="0.684785" v="270.000000" /><kf t="0.263789" v="45.000000" /><kf t="0.853504" v="45.000000" /><kf t="0.894378" v="45.000000" /><kf t="0.867732" v="270.000000" /></property><property name="freq" type="float" value="1.750000" /><property name="mag" type="float"><kf t="0.237144" v="0.000000" /><kf t="0.290434" v="0.000000" e="cubicBezier 0.0 0.0 0.58 1.0" /><kf t="0.263789" v="155.000000" e="cubicBezier 0.42 0.0 1.0 1.0" /><kf t="0.448495" v="0.000000" e="cubicBezier 0.0 0.0 0.58 1.0" /><kf t="0.500027" v="0.000000" /><kf t="0.473381" v="155.000000" e="cubicBezier 0.42 0.0 1.0 1.0" /><kf t="0.709619" v="0.000000" e="cubicBezier 0.0 0.0 0.58 1.0" /><kf t="0.658140" v="0.000000" /><kf t="0.684785" v="155.000000" e="cubicBezier 0.42 0.0 1.0 1.0" /></property><property name="type" type="int" value="0" /><property name="phase" type="float" value="0.000000" /></effect><effect id="com.alightcreative.effects.swing2" locallyApplied="true"><property name="freq" type="float" value="0.700000" /><property name="a1" type="float" value="-3.500000" /><property name="a2" type="float" value="4.000000" /><property name="phase" type="float" value="0.000000" /><property name="type" type="int" value="0" /></effect></nullobj>"""

    private val dysChild = """<shape id="205147370" startTime="0" endTime="3032" fillType="media" fillImage="content://media/external/images/media/1000267837" mediaFillMode="fill" parent="205147372" s=".rect"><transform><location value="-0.171814,-0.369141,0.000000" /><scale value="1.500000,1.500000" /><opacity><kf t="0.346306" v="1.000000" e="cubicBezier 0.42 0.0 0.58 1.0" /><kf t="0.002309" v="0.000000" /></opacity></transform><property name="size" type="vec2" value="360.000000,640.000000" /></shape>"""

    @Test
    fun verbatimDysNullCarriesItsChildren() {
        val r = AlightXmlImporter.parse(scene(dysNull + dysChild, total = 18765), 1080f)
        val clips = r.snapshot.tracks.flatMap { it.clips }
        val nullClip = clips.first { it.type == LayerType.NULL }
        val kid = clips.first { it.type == LayerType.SHAPE && it.parent != null }
        assertEquals(nullClip.id, kid.parent!!.id)
        assertEquals(-540f, kid.parent!!.refX, 0.01f)
        assertEquals(-960f, kid.parent!!.refY, 0.01f)
        // The child's stored pose is the naive reading of its LOCAL values (the corner it used to be
        // stranded at); the link is what moves it to the parent.
        assertEquals(-540.17f, kid.transformX, 0.05f)
        assertEquals(-960.37f, kid.transformY, 0.05f)
        val rig = rig(clips)
        // At t=0 both effect bakes sit at sin(0) = 0 and the null is on its first keys (centre, scale 1):
        // parent (540.17, 960.37) + 1.0 x local (-0.17, -0.37) = the scene centre the child renders at.
        val at0 = rig.adjusted(kid, 0L)
        assertEquals(0f, at0.transformX, 0.75f)
        assertEquals(0f, at0.transformY, 0.75f)
        assertEquals(1f, at0.scalePercent / kid.renderAt(0L).scalePercent, 0.01f)   // null scale 1.0 there
        // At the null's 1.28 scale key (t = 0.272635 x 18765 = 5116 ms, past the child's own span —
        // ParentRig evaluates the parent regardless) the child's scale grows by exactly that factor.
        val atPulse = rig.adjusted(kid, 5116L)
        assertEquals(1.28f, atPulse.scalePercent / kid.renderAt(5116L).scalePercent, 0.02f)
        // Later it stays near the centre (its own oscillate bake displaces it by tens of px at most),
        // not at the (-540, -960) corner the unpatched importer left it in.
        val at1000 = rig.adjusted(kid, 1000L)
        assertTrue(hypot(at1000.transformX, at1000.transformY) < 45f)
        assertTrue(r.warnings.none { it.contains("<nullobj>") })
    }

    /** 1c3ff763 (Subscriber), verbatim pieces: root null 635442, group 635441, and inside it the null
     * 635440 with shapes 635434 / 635437 parented to it and 635435 / 635438 parented to those shapes. */
    private val subscriber = """<nullobj id="635442" label="Null 1" startTime="0" endTime="6032" mediaFillMode="stretch" type="perspective"><transform><location value="960.000000,540.000000,0.000000" /><scale value="0.780000,0.780000" /></transform></nullobj><embedScene id="635441" label="Group 1" startTime="0" endTime="6032" fillType="intrinsic" outTime="6032" mediaFillMode="stretch" parent="635442"><transform><location><kf t="0.168435" v="0.000000,0.000000,0.000000" e="cubicBezier 0.29 0.0 0.0 1.0" /><kf t="0.831565" v="0.000000,0.000000,0.000000" /><kf t="0.002653" v="0.000000,1071.244873,0.000000" /><kf t="0.997347" v="0.000000,1057.828857,0.000000" e="cubicBezier 1.0 0.0 0.71000004 1.0" /></location><scale value="0.709751,0.709751" /></transform><scene title="" width="1920" height="1080" totalTime="6032" fps="30"><nullobj id="635440" label="Null 1" startTime="0" endTime="6032" mediaFillMode="stretch" type="perspective"><transform><location><kf t="0.417109" v="1092.240845,810.815186,0.000000" e="cubicBezier 0.54 0.0 0.0 1.0" /><kf t="0.334218" v="968.058350,810.815186,0.000000" /></location><scale value="0.737073,0.737073" /></transform></nullobj><shape id="635434" label="Shape 2" startTime="2133" endTime="6032" fillType="color" mediaFillMode="stretch" parent="635440" s=".circle"><transform><location><kf t="-0.004103" v="-21.516174,0.000000,0.000000" /><kf t="0.141062" v="133.486084,0.000000,0.000000" e="cubicBezier 0.54 0.0 0.0 1.0" /></location><scale><kf t="0.226468" v="1.000000,1.000000" /><kf t="0.354706" v="1.000000,1.000000" e="cubicBezier 0.19 0.0 0.32 1.0" /><kf t="0.294947" v="0.735000,0.735000" e="cubicBezier 0.68 0.0 0.81 1.0" /></scale></transform><fillColor value="#ffffffff" /><property name="size" type="vec2" value="100.000000,100.000000" /></shape><shape id="635435" label="1668583646374.png" startTime="3266" endTime="6032" fillType="media" fillImage="content://media/external/images/media/56214" mediaFillMode="stretch" parent="635434" s=".rect"><transform><scale value="0.081481,0.081481" /></transform><property name="size" type="vec2" value="540.000000,540.000000" /></shape><shape id="635437" label="Shape 1" startTime="0" endTime="6032" fillType="color" mediaFillMode="stretch" parent="635440" s=".roundrect"><transform><location><kf t="0.334218" v="-13.999939,0.000000,0.000000" /><kf t="0.417109" v="-308.499939,0.000000,0.000000" e="cubicBezier 0.54 0.0 0.0 1.0" /></location><scale><kf t="0.251326" v="1.000000,1.000000" /><kf t="0.334218" v="1.000000,1.000000" e="cubicBezier 0.19 0.0 0.32 1.0" /><kf t="0.295591" v="0.740000,0.740000" e="cubicBezier 0.67999995 0.0 0.8066667 1.0" /></scale></transform><fillColor value="#ff323232" /><property name="size" type="vec2" value="285.000000,100.000000" /><property name="cornerRadius" type="float" value="170.000000" /></shape><text id="635438" startTime="1866" endTime="6032" fillType="color" mediaFillMode="stretch" parent="635437" size="18.000000" font="imported?name=ArgentumSans-Medium.ttf" wrapWidth="512" align="center"><transform lockAspectRatio="false"><location value="0.500000,-10.500000,0.000000" /><scale value="1.423828,1.423828" /><opacity><kf t="0.027845" v="1.000000" /><kf t="0.003841" v="0.000000" /></opacity></transform><fillColor value="#ff323232" /><content>Subscribed</content></text></scene></embedScene>"""

    @Test
    fun verbatimSubscriberChainLinksAndLands() {
        val r = AlightXmlImporter.parse(scene(subscriber, w = 1920, h = 1080, total = 6333), 1920f)
        val clips = r.snapshot.tracks.flatMap { it.clips }
        val group = clips.first { it.type == LayerType.GROUP }
        val rootNull = clips.first { it.type == LayerType.NULL && it.parent == null }
        val innerNull = clips.first { it.type == LayerType.NULL && it.parent?.id == group.id }
        assertEquals(rootNull.id, group.parent!!.id)
        val shape2 = clips.first { it.name == "Shape 2" }
        val shape1 = clips.first { it.name == "Shape 1" }
        assertEquals(innerNull.id, shape2.parent!!.id)
        assertEquals(innerNull.id, shape1.parent!!.id)
        // Visible-layer parents get an invisible anchor mirroring their evaluated transform.
        val anchor2 = clips.first { it.name == "Shape 2 (parent)" }
        val anchor1 = clips.first { it.name == "Shape 1 (parent)" }
        assertEquals(LayerType.NULL, anchor2.type)
        assertEquals(innerNull.id, anchor2.parent!!.id)
        assertEquals(anchor2.id, clips.first { it.name == "1668583646374.png" }.parent!!.id)
        assertEquals(shape2.keyframes[AnimProp.POSITION], anchor2.keyframes[AnimProp.POSITION])
        assertEquals(anchor1.id, clips.first { it.type == LayerType.TEXT }.parent!!.id)
        assertTrue(r.warnings.none { it.contains("was not imported") })
        assertTrue(r.warnings.none { it.contains("<nullobj>") })
        // Numeric, at t = 2016 ms: the inner null sits exactly on its first location key
        // (968.06, 810.82; scale 0.737073) and Shape 1 on its own (-14, 0; scale 1). Composed by hand:
        // group world = root null (centre, 0.78) x embed (loc (0,0), 0.709751) -> pos (0,0), scale 0.5536;
        // inner null world -> (4.46, 149.94), scale 0.4081; Shape 1 -> (4.46 - 0.4081 x 14, 149.94).
        val adj = rig(clips).adjusted(shape1, 2016L)
        assertEquals(-1.25f, adj.transformX, 2f)
        assertEquals(149.94f, adj.transformY, 2f)
        assertEquals(0.4081f, adj.scalePercent / shape1.renderAt(2016L).scalePercent, 0.01f)
    }
}
