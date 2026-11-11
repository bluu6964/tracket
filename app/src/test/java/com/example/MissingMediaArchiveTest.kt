package com.example

import com.example.engine.MissingMedia
import com.example.engine.ProjectArchive
import com.example.engine.ProjectFiles
import com.example.model.LayerType
import com.example.model.ProjectSnapshot
import com.example.model.TimelineClip
import com.example.model.TimelineTrack
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

/**
 * Media substitution (build 111) end to end through the project file: a preset whose picture lived
 * on another phone is substituted with a file copied into this phone's own storage (the
 * [com.example.engine.MediaImporter.copyToImports] result), and the substituted project must then
 * pack into a `.ampro` and come back whole on a second phone — the replacement behaves exactly like
 * media localised at import (build 98 / 105). Runs in the Gradle suite (real serialization); the
 * harness covers the pure half in [MissingMediaTest].
 */
class MissingMediaArchiveTest {

    private lateinit var base: File
    private lateinit var phoneA: File
    private lateinit var phoneB: File
    private val ownAuthority = "com.example.fileprovider"

    @Before
    fun setUp() {
        base = Files.createTempDirectory("am_missing_media").toFile()
        phoneA = File(base, "a").apply { mkdirs() }
        phoneB = File(base, "b").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        base.deleteRecursively()
    }

    @Test
    fun aSubstitutedFileSurvivesTheProjectArchive() {
        // The user's own logo, copied into the app's imports pool by the substitution.
        File(phoneA, "imports").mkdirs()
        File(phoneA, "imports/my-logo.png").writeBytes(ByteArray(3000) { (it % 253).toByte() })
        File(phoneA, "imports/my-clip.mp4").writeBytes(ByteArray(4000) { (it % 241).toByte() })
        val myLogo = "content://$ownAuthority/imports/my-logo.png"
        val myClip = "content://$ownAuthority/imports/my-clip.mp4"

        // As imported from a collected preset: both fills point at the author's phone.
        val logoShape = TimelineClip(
            id = "logo", name = "images.png", type = LayerType.SHAPE, startMs = 0, durationMs = 3000, color = 0,
            fillImageUri = "content://media/external/images/media/1000033464"
        )
        val videoShape = TimelineClip(
            id = "vid", name = "intro.mp4", type = LayerType.SHAPE, startMs = 0, durationMs = 3000, color = 0,
            fillVideoUri = "am:5A2183027F5DE730E692BBF35E5CB75D328D856A.mp4"
        )
        val imported = ProjectSnapshot(tracks = listOf(TimelineTrack("t", "L", clips = listOf(logoShape, videoShape))))
        assertEquals(2, MissingMedia.collect(imported, ownAuthority).size)

        var snap = MissingMedia.substitute(imported, logoShape.fillImageUri!!, myLogo)
        snap = MissingMedia.substitute(snap, videoShape.fillVideoUri!!, myClip)
        assertTrue(MissingMedia.collect(snap, ownAuthority).isEmpty())

        val out = ByteArrayOutputStream()
        val report = ProjectArchive.export(snap, "Substituted", "test", phoneA, out)
        assertEquals(2, report.assetCount)
        assertTrue(report.notPacked.isEmpty())

        val r = ProjectArchive.import(ByteArrayInputStream(out.toByteArray()), phoneB) { f ->
            "content://$ownAuthority/" + f.relativeTo(phoneB).path
        }
        assertTrue(r.missing.isEmpty())
        val clips = r.snapshot.tracks.flatMap { it.clips }
        val logoFile = ProjectFiles.fileOf(clips.single { it.id == "logo" }.fillImageUri!!, phoneB)
        val clipFile = ProjectFiles.fileOf(clips.single { it.id == "vid" }.fillVideoUri!!, phoneB)
        assertTrue(logoFile != null && logoFile.exists() && logoFile.length() == 3000L)
        assertTrue(clipFile != null && clipFile.exists() && clipFile.length() == 4000L)
        // On the new phone nothing is missing any more, by either definition.
        assertTrue(MissingMedia.collect(r.snapshot, ownAuthority).isEmpty())
        assertTrue(ProjectFiles.missingMedia(r.snapshot.tracks, phoneB).isEmpty())
    }
}
