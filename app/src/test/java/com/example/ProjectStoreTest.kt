package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.engine.ProjectStore
import com.example.model.AspectRatio
import com.example.model.EditorProjectSnapshot
import com.example.model.LayerType
import com.example.model.PreviewResolution
import com.example.model.TimelineClip
import com.example.model.TimelineTrack
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProjectStoreTest {

    @Test
    fun `project snapshot round trips all clip fields`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.filesDir, "tracket_project.json")
        file.delete()

        val clip = TimelineClip(
            id = "clip-1",
            trackIndex = 0,
            name = "sample.mp4",
            type = LayerType.VIDEO,
            startMs = 1_200L,
            durationMs = 3_400L,
            color = 0xFF112233,
            text = "caption",
            textColor = 0xFFAABBCC,
            shapePattern = "circle",
            volume = 0.6f,
            speed = 1.25f,
            isMuted = true,
            isLocked = true,
            isFlippedH = true,
            isFlippedV = false,
            blendMode = "Multiply",
            effectName = "Warm",
            animationName = "Fade",
            uri = "content://media/video/1",
            mimeType = "video/mp4",
            isImage = false,
            sourceDurationMs = 5_000L,
            trimStartMs = 400L
        )
        val snapshot = EditorProjectSnapshot(
            tracks = listOf(
                TimelineTrack(
                    id = "main",
                    name = "Main Video",
                    type = LayerType.VIDEO,
                    clips = listOf(clip)
                )
            ),
            playheadMs = 2_000L,
            aspectRatio = AspectRatio.RATIO_9_16,
            previewResolution = PreviewResolution.HALF,
            isMasterMuted = true
        )

        try {
            val store = ProjectStore(context)
            store.save(snapshot)
            assertEquals(snapshot, store.load())
        } finally {
            file.delete()
            File(context.filesDir, "tracket_project.json.tmp").delete()
        }
    }
}
