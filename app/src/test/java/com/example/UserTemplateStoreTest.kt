package com.example

import com.example.engine.UserTemplateStore
import com.example.model.AnimProp
import com.example.model.ClipFxLibrary
import com.example.model.GroupOps
import com.example.model.Keyframe
import com.example.model.LayerType
import com.example.model.ParentLink
import com.example.model.TimelineClip
import com.example.model.TimelineTrack
import com.example.model.UserTemplateFiles
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * "Save as Template" (Templates v2): the on-disk store — a saved group survives as JSON +
 * its own media copies under filesDir/templates, loads back byte-equal, lists, and deletes
 * cleanly. Runs under Gradle only (real kotlinx serialization), like ProjectStoreTest;
 * the model rules are pinned separately in UserTemplateTest (harness).
 */
class UserTemplateStoreTest {

    private lateinit var dir: File
    private lateinit var filesDir: File
    private lateinit var store: UserTemplateStore

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("am_usertemplates").toFile()
        filesDir = File(dir, "files").apply { mkdirs() }
        store = UserTemplateStore(File(dir, "templates"), filesDir)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun lane(clip: TimelineClip) = TimelineTrack(id = "lane-${clip.id}", name = "Layer", clips = listOf(clip))

    /** A group with a Null rig, a Controller, a connected card and an image child whose
     *  picture lives in the app's imports (the source project's file). */
    private fun fixture(): List<TimelineTrack> {
        val gLink = GroupOps.linkTo("g1", GroupOps.Pose.REST)
        fun clip(id: String, type: LayerType, groupId: String? = null, parent: ParentLink? = null) = TimelineClip(
            id = id, name = id, type = type, startMs = 2000L, durationMs = 3000L,
            color = 0xFF445566L, groupId = groupId, parent = parent
        )
        val group = clip("g1", LayerType.GROUP)
        val rig = clip("g1-null", LayerType.NULL, "g1", gLink).copy(
            keyframes = mapOf(AnimProp.ROTATION to listOf(Keyframe(0L, 0f), Keyframe(3000L, 360f)))
        )
        val ctrl = clip("g1-ctrl", LayerType.CONTROLLER, "g1", gLink)
        val card = clip("g1-card", LayerType.SHAPE, "g1", ParentLink("g1-null", 1f, 2f, 100f, 0f, lockRotate = true)).copy(
            controllerId = "g1-ctrl",
            effects = listOf(ClipFxLibrary.create(ClipFxLibrary.GLOW)!!)
        )
        val image = clip("g1-img", LayerType.SHAPE, "g1", gLink).copy(
            fillMode = com.example.model.FILL_IMAGE,
            fillImageUri = "content://com.example.fileprovider/imports/photo.png"
        )
        return listOf(lane(group), lane(rig), lane(ctrl), lane(card), lane(image))
    }

    @Test
    fun saveLoadRoundTripPreservesTheSubtree() {
        val imports = File(filesDir, "imports").apply { mkdirs() }
        File(imports, "photo.png").writeBytes(byteArrayOf(10, 20, 30))
        val saved = store.save("My Template", fixture(), "g1")
        assertNotNull(saved)
        saved!!
        assertEquals("My Template", saved.name)
        assertEquals(5, saved.clips.size)
        // The image reference was rewritten to the template's own copy.
        val img = saved.clips.first { it.id == "g1-img" }
        assertTrue(img.fillImageUri!!.startsWith("file://"))
        assertTrue(img.fillImageUri!!.contains("templates"))
        val copy = UserTemplateFiles.resolveRefFile(img.fillImageUri!!, filesDir)
        assertNotNull(copy)
        assertTrue(copy!!.exists())
        assertEquals(3L, copy.length())
        // Listing and loading give the same template back, field for field.
        assertEquals(listOf(saved.id), store.list().map { it.id })
        assertEquals(saved, store.load(saved.id))
    }

    @Test
    fun savedMediaSurvivesTheSourceProjectsDeletion() {
        val imports = File(filesDir, "imports").apply { mkdirs() }
        File(imports, "photo.png").writeBytes(byteArrayOf(1))
        val saved = store.save("T", fixture(), "g1")!!
        imports.deleteRecursively()   // the source project and its imports are gone
        val loaded = store.load(saved.id)!!
        val ref = loaded.clips.first { it.id == "g1-img" }.fillImageUri!!
        assertTrue(UserTemplateFiles.resolveRefFile(ref, filesDir)!!.exists())
    }

    @Test
    fun deleteRemovesTheTemplateAndItsCopies() {
        val imports = File(filesDir, "imports").apply { mkdirs() }
        File(imports, "photo.png").writeBytes(byteArrayOf(1))
        val saved = store.save("T", fixture(), "g1")!!
        val tplDir = File(dir, "templates/${saved.id}")
        assertTrue(tplDir.exists())
        store.delete(saved.id)
        assertTrue(store.list().isEmpty())
        assertNull(store.load(saved.id))
        assertTrue(!tplDir.exists())
    }

    @Test
    fun saveOfANonGroupIsNull() {
        assertNull(store.save("X", fixture(), "g1-card"))
        assertNull(store.save("X", fixture(), "missing"))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun twoTemplatesCoexist() {
        val a = store.save("A", fixture(), "g1")!!
        val b = store.save("B", fixture(), "g1")!!
        assertEquals(setOf(a.id, b.id), store.list().map { it.id }.toSet())
        store.delete(a.id)
        assertEquals(listOf(b.id), store.list().map { it.id })
    }
}
