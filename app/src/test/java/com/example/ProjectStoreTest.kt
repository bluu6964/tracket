package com.example

import com.example.engine.ProjectFiles
import com.example.engine.ProjectStore
import com.example.model.AnimDir
import com.example.model.AnimProp
import com.example.model.AspectRatio
import com.example.model.ClipAnim
import com.example.model.ClipFx
import com.example.model.ClipMask
import com.example.model.CornerPin
import com.example.model.Easing
import com.example.model.ExportSettings
import com.example.model.Keyframe
import com.example.model.LayerType
import com.example.model.MaskKey
import com.example.model.MaskMode
import com.example.model.MaskPoint
import com.example.model.MaskShape
import com.example.model.MaskState
import com.example.model.MaskTrack
import com.example.model.ParentLink
import com.example.model.PlanarRemove
import com.example.model.PreviewResolution
import com.example.model.ProjectSnapshot
import com.example.model.TimelineClip
import com.example.model.TimelineTrack
import com.example.model.TrackFollow
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ProjectStoreTest {

    private lateinit var dir: File
    private lateinit var store: ProjectStore

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("am_projects").toFile()
        store = ProjectStore(File(dir, "projects"))
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    /** A layer that uses every kind of saved data: keyframes with an enum key, effects, masks, tracks, pins, parent. */
    private fun richClip(): TimelineClip {
        val pin = CornerPin("v1", longArrayOf(0L, 40L), FloatArray(16) { it / 20f }, 16f / 9f)
        return TimelineClip(
            id = "c1", name = "Clip one", type = LayerType.VIDEO, startMs = 500L, durationMs = 4000L, color = 0xFF112233L,
            text = "Hello বাংলা", uri = "content://com.example.fileprovider/imports/a.mp4", mimeType = "video/mp4",
            sourceDurationMs = 9000L, trimStartMs = 250L, opacity = 70f, blendMode = "Multiply",
            keyframes = mapOf(
                AnimProp.POSITION to listOf(Keyframe(0L, 1f, 2f), Keyframe(1000L, 30f, 40f, Easing(0.42f, 0f, 0.58f, 1f), tag = 1)),
                AnimProp.OPACITY to listOf(Keyframe(0L, 100f))
            ),
            animIn = ClipAnim("fade", AnimDir.LEFT, 500L, Easing.LINEAR),
            effects = listOf(ClipFx(id = "fx1", type = "blur", enabled = false, values = listOf(12f, 3f))),
            masks = listOf(
                ClipMask(
                    id = "m1", shape = MaskShape.PATH, mode = MaskMode.SUBTRACT, inverted = true,
                    points = listOf(MaskPoint(0.1f, 0.2f), MaskPoint(0.5f, 0.9f), MaskPoint(0.9f, 0.2f)), smooth = true,
                    keys = listOf(MaskKey(0L, MaskState(feather = 5f)), MaskKey(800L, MaskState(cx = 0.7f))),
                    track = MaskTrack(
                        srcMs = longArrayOf(0L, 40L), u = floatArrayOf(0.1f, 0.2f), v = floatArrayOf(0.3f, 0.4f),
                        rotDeg = floatArrayOf(0f, 1f), scale = floatArrayOf(1f, 1.1f), aspect = 1.5f,
                        followRotation = true, followScale = false
                    )
                )
            ),
            trackFollow = TrackFollow("v9", longArrayOf(0L, 40L, 80L), floatArrayOf(0f, 1f, 2f), floatArrayOf(0f, -1f, -2f),
                rotDeg = floatArrayOf(0f, 5f, 9f), scale = null),
            cornerPin = pin,
            planarRemove = PlanarRemove(pin, floatArrayOf(1f, 0.9f), "/data/x/patch.bin", usedFrames = true),
            parent = ParentLink("null1", 1f, 2f, 100f, 0f),
            groupId = "g1"
        )
    }

    private fun snapshot() = ProjectSnapshot(
        tracks = listOf(TimelineTrack("t1", "Lane 1", clips = listOf(richClip())), TimelineTrack("t2", "Lane 2", isMuted = true)),
        aspectRatio = AspectRatio.RATIO_9_16,
        canvasColor = 0xFF223344L,
        exportSettings = ExportSettings(resolution = "720p HD", frameRate = 60, customBitrateKbps = 8000),
        previewResolution = PreviewResolution.HALF,
        isMasterMuted = true,
        playheadMs = 1234L,
        beatMarksMs = listOf(500L, 1500L, 2250L)
    )

    @Test
    fun roundTripKeepsEverything() {
        val s = snapshot()
        val text = ProjectStore.encode(s)
        val back = ProjectStore.decode(text)

        // The plain classes (arrays) have no equals: a second encode must give the same text.
        assertEquals(text, ProjectStore.encode(back))

        val c = back.tracks[0].clips[0]
        assertEquals(richClip().copy(trackFollow = null, cornerPin = null, planarRemove = null, masks = emptyList()),
            c.copy(trackFollow = null, cornerPin = null, planarRemove = null, masks = emptyList()))
        assertArrayEquals(longArrayOf(0L, 40L, 80L), c.trackFollow!!.timesMs)
        assertArrayEquals(floatArrayOf(0f, 5f, 9f), c.trackFollow!!.rotDeg!!, 0f)
        assertNull(c.trackFollow!!.scale)
        assertEquals(2, c.cornerPin!!.size)
        assertEquals("/data/x/patch.bin", c.planarRemove!!.patchPath)
        assertEquals(2, c.masks[0].keys.size)
        assertEquals(true, c.masks[0].track!!.followRotation)
        assertEquals(listOf(Keyframe(0L, 1f, 2f), Keyframe(1000L, 30f, 40f, Easing(0.42f, 0f, 0.58f, 1f), tag = 1)),
            c.keyframes[AnimProp.POSITION])
        assertEquals(AspectRatio.RATIO_9_16, back.aspectRatio)
        assertEquals(PreviewResolution.HALF, back.previewResolution)
        assertEquals(60, back.exportSettings.frameRate)
        assertEquals(0xFF223344L, back.canvasColor)
        assertEquals(1234L, back.playheadMs)
        assertEquals(listOf(500L, 1500L, 2250L), back.beatMarksMs)
        assertTrue(back.isMasterMuted)
    }

    @Test
    fun olderFileWithMissingFieldsAndNewerFileWithExtraFieldsBothLoad() {
        val minimal = """{"tracks":[{"id":"t","name":"L","clips":[{"id":"c","name":"n","type":"TEXT","startMs":0,"durationMs":1000,"color":1,"futureField":42}]}],"someNewThing":true}"""
        val s = ProjectStore.decode(minimal)
        val c = s.tracks[0].clips[0]
        assertEquals(LayerType.TEXT, c.type)
        assertEquals(100f, c.opacity, 0f)          // default
        assertEquals(AspectRatio.RATIO_16_9, s.aspectRatio)
    }

    @Test
    fun shapeCornerRadiusRoundTripsAndDefaultsToUnsetInOldFiles() {
        val pill = TimelineClip(
            id = "s", name = "Pill", type = LayerType.SHAPE, startMs = 0L, durationMs = 1000L, color = 0xFFFF3377L,
            shapePattern = "ROUNDED_RECT", shapeCornerRadius = 41.5f
        )
        val snap = ProjectSnapshot(tracks = listOf(TimelineTrack("t", "L", clips = listOf(pill))))
        val text = ProjectStore.encode(snap)
        assertTrue(text.contains("\"shapeCornerRadius\":41.5"))
        assertEquals(41.5f, ProjectStore.decode(text).tracks[0].clips[0].shapeCornerRadius, 0.001f)

        // A file written before the field existed has no such key: the radius loads as unset (-1).
        val old = """{"tracks":[{"id":"t","name":"L","clips":[{"id":"s","name":"Pill","type":"SHAPE","startMs":0,"durationMs":1000,"color":4294908775,"shapePattern":"ROUNDED_RECT"}]}]}"""
        assertEquals(-1f, ProjectStore.decode(old).tracks[0].clips[0].shapeCornerRadius, 0f)
    }

    @Test
    fun unknownEnumValueFallsBackToTheDefault() {
        val text = """{"previewResolution":"EIGHT_K","tracks":[]}"""
        assertEquals(PreviewResolution.FULL, ProjectStore.decode(text).previewResolution)
    }

    @Test
    fun createSaveLoadListRenameDelete() {
        val m = store.create("My video")
        assertEquals("My video", m.name)
        assertEquals(0, store.load(m.id)!!.tracks.size)

        val saved = store.save(m.id, snapshot())
        assertEquals("My video", saved.name)       // a save keeps the name
        assertEquals(1, saved.layerCount)
        assertEquals(4500L, saved.durationMs)
        assertEquals(1, store.load(m.id)!!.tracks[0].clips.size)

        store.rename(m.id, "  Renamed  ")
        assertEquals("Renamed", store.list().single().name)
        store.rename(m.id, "   ")                  // blank names are ignored
        assertEquals("Renamed", store.list().single().name)

        val second = store.create("My video")
        val third = store.create("My video")
        assertEquals("My video 2", third.name)
        assertEquals(3, store.list().size)

        store.delete(second.id)
        assertNull(store.load(second.id))
        assertEquals(2, store.list().size)
    }

    @Test
    fun brokenFileFallsBackToTheBackup() {
        val m = store.create("P")
        store.save(m.id, snapshot())                                 // project.json
        store.save(m.id, snapshot().copy(playheadMs = 99L))          // old one becomes project.json.bak
        val main = File(File(dir, "projects/${m.id}"), ProjectStore.PROJECT_FILE)
        main.writeText("{ this is not json")                         // a crash in the middle of a write
        val loaded = store.load(m.id)
        assertNotNull(loaded)
        assertEquals(1234L, loaded!!.playheadMs)                     // the backup (the save before the last one)
    }

    @Test
    fun brokenMetaIsRebuiltFromTheProject() {
        val m = store.create("P")
        store.save(m.id, snapshot())
        File(File(dir, "projects/${m.id}"), ProjectStore.META_FILE).writeText("garbage")
        val l = store.list()
        assertEquals(1, l.size)
        assertEquals(1, l[0].layerCount)
    }

    @Test
    fun openLastOrCreate() {
        val (id1, s1) = store.openLastOrCreate()          // nothing yet: a new empty project
        assertTrue(s1.tracks.isEmpty())
        assertEquals(1, store.list().size)

        store.save(id1, snapshot())
        val other = store.create("Other")
        store.lastId = id1
        assertEquals(id1, store.openLastOrCreate().first)
        store.lastId = other.id
        assertEquals(other.id, store.openLastOrCreate().first)

        store.delete(other.id)                            // last project deleted: falls back to the newest left
        assertEquals(id1, store.openLastOrCreate().first)
        assertNull(store.lastId)
    }

    @Test
    fun deleteIgnoresDangerousIds() {
        val m = store.create("P")
        store.delete("../projects")
        store.delete("")
        assertNotNull(store.load(m.id))
    }

    @Test
    fun missingMediaIsFoundByFileProviderPath() {
        val files = File(dir, "files").apply { mkdirs() }
        File(files, "imports").mkdirs()
        File(files, "imports/here.mp4").writeText("x")
        fun clip(name: String, uri: String?) = TimelineClip(
            id = name, name = name, type = LayerType.VIDEO, startMs = 0L, durationMs = 1000L, color = 0L, uri = uri
        )
        val tracks = listOf(
            TimelineTrack("t", "L", clips = listOf(
                clip("present", "content://com.example.fileprovider/imports/here.mp4"),
                clip("gone", "content://com.example.fileprovider/imports/gone.mp4"),
                clip("unknown", "content://media/external/video/1"),
                clip("none", null)
            ))
        )
        assertEquals(listOf("gone"), ProjectFiles.missingMedia(tracks, files))
        assertNull(ProjectFiles.fileOf("content://x/imports/../../secret", files))
        assertFalse(ProjectFiles.fileOf("content://com.example.fileprovider/imports/a.mp4", files)!!.exists())
    }

    @Test
    fun prePushFileLoadsWithPushFieldDefaults() {
        // A project file written before the production push (builds 188-197):
        // no "markers" key on the snapshot, and clips without the ducking
        // (194), noise-reduction (195) or text-on-path (191) fields. Every one
        // must load at its documented default (build 198 regression pin).
        val old = """{"tracks":[{"id":"t","name":"L","clips":[
          {"id":"a","name":"Music","type":"AUDIO","startMs":0,"durationMs":4000,"color":1,"uri":"file:///m.mp3"},
          {"id":"x","name":"Title","type":"TEXT","startMs":0,"durationMs":2000,"color":1,"text":"Hi"}
        ]}]}"""
        val s = ProjectStore.decode(old)
        assertTrue(s.markers.isEmpty())
        val a = s.tracks[0].clips[0]
        assertEquals(false, a.duckEnabled)
        assertNull(a.duckTriggerId)
        assertEquals(12f, a.duckAmountDb, 0f)
        assertEquals(0.5f, a.duckSmoothness, 0f)
        assertEquals(false, a.nrEnabled)
        assertEquals(0.6f, a.nrStrength, 0f)
        val x = s.tracks[0].clips[1]
        assertNull(x.textPathLayerId)
        assertEquals(0f, x.textPathStart, 0f)
    }
}
