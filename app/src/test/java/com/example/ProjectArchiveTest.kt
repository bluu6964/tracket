package com.example

import com.example.engine.ProjectArchive
import com.example.engine.ProjectFiles
import com.example.model.ClipFx
import com.example.model.ClipFxLibrary
import com.example.model.CornerPin
import com.example.model.LayerType
import com.example.model.PlanarRemove
import com.example.model.ProjectSnapshot
import com.example.model.TimelineClip
import com.example.model.TimelineTrack
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ProjectArchiveTest {

    private lateinit var base: File
    private lateinit var phoneA: File
    private lateinit var phoneB: File

    @Before
    fun setUp() {
        base = Files.createTempDirectory("am_archive").toFile()
        phoneA = File(base, "a").apply { mkdirs() }
        phoneB = File(base, "b").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        base.deleteRecursively()
    }

    private fun clip(id: String, uri: String?) = TimelineClip(
        id = id, name = id, type = LayerType.VIDEO, startMs = 0L, durationMs = 1000L, color = 0L, uri = uri
    )

    /** A project on "phone A" that uses a video, a cutout folder, a remove patch and a file outside the app. */
    private fun projectOnA(): ProjectSnapshot {
        File(phoneA, "imports").mkdirs()
        File(phoneA, "imports/video.mp4").writeBytes(ByteArray(5000) { (it % 251).toByte() })
        File(phoneA, "cutouts/u1").mkdirs()
        File(phoneA, "cutouts/u1/f0.png").writeText("mask0")
        File(phoneA, "cutouts/u1/f1.png").writeText("mask1")
        File(phoneA, "remove").mkdirs()
        File(phoneA, "remove/patch.bin").writeText("patch")
        val pin = CornerPin("c1", longArrayOf(0L), FloatArray(8), 1.5f)
        val c1 = clip("c1", "content://com.example.fileprovider/imports/video.mp4").copy(
            cutoutDir = File(phoneA, "cutouts/u1").absolutePath,
            planarRemove = PlanarRemove(pin, floatArrayOf(1f), File(phoneA, "remove/patch.bin").absolutePath, true)
        )
        val c2 = clip("c2", "content://com.example.fileprovider/imports/video.mp4")     // same file: packed once
        val c3 = clip("c3", "content://media/external/video/7")                          // not an app file
        val c4 = clip("c4", "content://com.example.fileprovider/imports/deleted.mp4")    // already gone
        return ProjectSnapshot(tracks = listOf(TimelineTrack("t", "L", clips = listOf(c1, c2, c3, c4))), playheadMs = 77L)
    }

    private fun export(): Pair<ByteArray, ProjectArchive.ExportReport> {
        val out = ByteArrayOutputStream()
        val report = ProjectArchive.export(projectOnA(), "Trip", "0.1.102", phoneA, out)
        return out.toByteArray() to report
    }

    @Test
    fun exportThenImportOnAnotherPhoneBringsEverythingBack() {
        val (bytes, report) = export()
        assertEquals(4, report.assetCount)                 // video once + 2 mask files + patch
        assertEquals(listOf("c3", "c4"), report.notPacked)

        val r = ProjectArchive.import(ByteArrayInputStream(bytes), phoneB) { "file://" + it.absolutePath }
        assertEquals("Trip", r.name)
        assertEquals(77L, r.snapshot.playheadMs)
        val clips = r.snapshot.tracks[0].clips

        val video = File(clips[0].uri!!.removePrefix("file://"))
        assertTrue(video.exists())
        assertTrue(video.path.startsWith(File(phoneB, "imports").canonicalPath))
        assertEquals(5000L, video.length())
        assertEquals(clips[0].uri, clips[1].uri)           // same media, same file

        val cut = File(clips[0].cutoutDir!!)
        assertTrue(cut.isDirectory)
        assertEquals(setOf("f0.png", "f1.png"), cut.list()!!.toSet())
        assertEquals("mask1", File(cut, "f1.png").readText())
        assertEquals("patch", File(clips[0].planarRemove!!.patchPath).readText())

        assertEquals("content://media/external/video/7", clips[2].uri)   // left as it was
        assertTrue(r.missing.isEmpty())
        assertTrue(ProjectFiles.missingMedia(listOf(TimelineTrack("t", "L", clips = clips.take(2))), phoneB).isEmpty())
    }

    @Test
    fun overCapEffectStackRoundTrips() {
        // Queue 14h (build 130): a stack past the old 12-effect cap must survive an archive
        // round trip whole — the file format stores the effect list unbounded; only the
        // renderer's op array is bounded (32). The type order is the corpus's largest stack
        // (blxze-brepizx-pack, 29 effects).
        val k = ClipFxLibrary
        val types = listOf(
            k.CHROMA_KEY_AM, k.CHROMA_KEY_AM, k.CHROMA_KEY_AM, k.CHROMA_KEY_AM, k.CHROMA_KEY_AM,
            k.SOFT_GLOW_AM, k.CHROMA_KEY_AM, k.CHROMA_KEY_AM, k.CHROMA_KEY_AM,
            k.HUE_SAT, k.COLOR_HOT_AM, k.COLORIZE_AM, k.HIGHLIGHT_SHADOW_AM, k.COLOR_HOT_AM,
            k.LIGHT_GLOW_AM, k.CHROMA_KEY_AM, k.TURBULENT_DISPLACE3_AM, k.EDGE_GLOW_AM,
            k.HUE_SAT, k.COLOR_HOT_AM, k.EDGE_GLOW_AM, k.COLORIZE_AM, k.HIGHLIGHT_SHADOW_AM,
            k.COLOR_TEMP_AM, k.HIGHLIGHT_SHADOW_AM, k.HUE_SAT, k.EXPOSURE, k.HUE_SAT, k.HUE_SAT
        )
        val fx = types.mapIndexed { i, t -> ClipFx(type = t, values = listOf(i.toFloat(), 0.5f)) }
        val snap = ProjectSnapshot(
            tracks = listOf(TimelineTrack("t", "L", clips = listOf(clip("fxstack", null).copy(effects = fx))))
        )
        val out = ByteArrayOutputStream()
        ProjectArchive.export(snap, "Fx", "0.1.102", phoneA, out)
        val r = ProjectArchive.import(ByteArrayInputStream(out.toByteArray()), phoneB) { "file://" + it.absolutePath }
        assertEquals(fx, r.snapshot.tracks[0].clips.single().effects)
    }

    @Test
    fun theArchiveHoldsNoPathsOfTheOldPhone() {
        val (bytes, _) = export()
        var projectJson = ""
        java.util.zip.ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.name == "project.json") projectJson = String(z.readBytes(), Charsets.UTF_8)
            }
        }
        assertTrue(projectJson.isNotEmpty())
        assertFalse(projectJson.contains(phoneA.absolutePath))
        assertTrue(projectJson.contains(ProjectArchive.TOKEN))
    }

    @Test
    fun notAProjectFileIsRefusedAndLeavesNothingBehind() {
        val junk = ByteArrayOutputStream().also { o ->
            ZipOutputStream(o).use { z -> z.putNextEntry(ZipEntry("hello.txt")); z.write(1); z.closeEntry() }
        }.toByteArray()
        try {
            ProjectArchive.import(ByteArrayInputStream(junk), phoneB) { it.path }
            fail("should have been refused")
        } catch (e: IllegalArgumentException) {
            assertNotNull(e.message)
        }
        assertTrue(File(phoneB, "imports").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun aNewerFormatIsRefused() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("manifest.json"))
            z.write("""{"format":"after-motion-project","formatVersion":99,"projectName":"x"}""".toByteArray())
            z.closeEntry()
            z.putNextEntry(ZipEntry("project.json"))
            z.write("""{"tracks":[]}""".toByteArray())
            z.closeEntry()
        }
        try {
            ProjectArchive.import(ByteArrayInputStream(out.toByteArray()), phoneB) { it.path }
            fail("should have been refused")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("newer"))
        }
    }

    @Test
    fun zipSlipEntriesAreIgnored() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("manifest.json"))
            z.write("""{"format":"after-motion-project","formatVersion":1,"projectName":"evil"}""".toByteArray())
            z.closeEntry()
            z.putNextEntry(ZipEntry("project.json"))
            z.write("""{"tracks":[{"id":"t","name":"L","clips":[{"id":"c","name":"bad","type":"VIDEO","startMs":0,"durationMs":1,"color":0,"uri":"am-asset:../../escaped.txt"}]}]}""".toByteArray())
            z.closeEntry()
            z.putNextEntry(ZipEntry("assets/../../escaped.txt"))
            z.write("pwned".toByteArray())
            z.closeEntry()
        }
        val r = ProjectArchive.import(ByteArrayInputStream(out.toByteArray()), phoneB) { it.path }
        assertFalse(File(base, "escaped.txt").exists())
        assertFalse(File(phoneB, "escaped.txt").exists())
        assertEquals(listOf("bad"), r.missing)             // the token did not resolve outside the folder
    }

    @Test
    fun aSecondExportOfAnImportedProjectStillWorks() {
        val (bytes, _) = export()
        val first = ProjectArchive.import(ByteArrayInputStream(bytes), phoneB) { "content://com.example.fileprovider/" + it.relativeTo(phoneB).path }
        val out = ByteArrayOutputStream()
        val report = ProjectArchive.export(first.snapshot.copy(
            tracks = listOf(first.snapshot.tracks[0].copy(clips = first.snapshot.tracks[0].clips.take(1)))
        ), "Again", "0.1.102", phoneB, out)
        assertEquals(4, report.assetCount)
        assertTrue(report.notPacked.isEmpty())
    }
}
