package com.example.engine

import android.content.Context
import com.example.model.AspectRatio
import com.example.model.EditorProjectSnapshot
import com.example.model.LayerType
import com.example.model.PreviewResolution
import com.example.model.TimelineClip
import com.example.model.TimelineTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Stores the current editing session in the app's private files directory. */
class ProjectStore(context: Context) {
    private val projectDirectory = context.applicationContext.filesDir
    private val projectFile = File(projectDirectory, FILE_NAME)
    private val saveMutex = Mutex()

    suspend fun load(): EditorProjectSnapshot? = withContext(Dispatchers.IO) {
        if (!projectFile.isFile) return@withContext null
        runCatching { decode(JSONObject(projectFile.readText())) }.getOrNull()
    }

    suspend fun save(snapshot: EditorProjectSnapshot) {
        saveMutex.lock()
        try {
            withContext(Dispatchers.IO) {
                projectDirectory.mkdirs()
                val tempFile = File(projectDirectory, "$FILE_NAME.tmp")
                tempFile.writeText(encode(snapshot).toString())
                if (!tempFile.renameTo(projectFile)) {
                    tempFile.copyTo(projectFile, overwrite = true)
                    tempFile.delete()
                }
            }
        } finally {
            saveMutex.unlock()
        }
    }

    private fun encode(snapshot: EditorProjectSnapshot): JSONObject = JSONObject().apply {
        put("schemaVersion", SCHEMA_VERSION)
        put("playheadMs", snapshot.playheadMs)
        put("aspectRatio", snapshot.aspectRatio.name)
        put("previewResolution", snapshot.previewResolution.name)
        put("isMasterMuted", snapshot.isMasterMuted)
        put("tracks", JSONArray().apply {
            snapshot.tracks.forEach { track -> put(track.toJson()) }
        })
    }

    private fun decode(root: JSONObject): EditorProjectSnapshot? {
        if (root.optInt("schemaVersion", -1) != SCHEMA_VERSION) return null
        val tracksJson = root.optJSONArray("tracks") ?: return null
        val tracks = buildList {
            for (index in 0 until tracksJson.length()) {
                val trackJson = tracksJson.optJSONObject(index) ?: continue
                add(trackJson.toTrack())
            }
        }
        if (tracks.isEmpty()) return null

        return EditorProjectSnapshot(
            tracks = tracks,
            playheadMs = root.optLong("playheadMs", 0L).coerceAtLeast(0L),
            aspectRatio = enumValueOrDefault(root.optString("aspectRatio"), AspectRatio.RATIO_16_9),
            previewResolution = enumValueOrDefault(
                root.optString("previewResolution"),
                PreviewResolution.FULL
            ),
            isMasterMuted = root.optBoolean("isMasterMuted", false)
        )
    }

    private fun TimelineTrack.toJson() = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("type", type.name)
        put("isMuted", isMuted)
        put("isLocked", isLocked)
        put("clips", JSONArray().apply { clips.forEach { put(it.toJson()) } })
    }

    private fun TimelineClip.toJson() = JSONObject().apply {
        put("id", id)
        put("trackIndex", trackIndex)
        put("name", name)
        put("type", type.name)
        put("startMs", startMs)
        put("durationMs", durationMs)
        put("color", color)
        putNullable("drawableResId", drawableResId)
        putNullable("text", text)
        put("textColor", textColor)
        putNullable("shapePattern", shapePattern)
        put("volume", volume.toDouble())
        put("speed", speed.toDouble())
        put("isMuted", isMuted)
        put("isLocked", isLocked)
        put("isFlippedH", isFlippedH)
        put("isFlippedV", isFlippedV)
        put("blendMode", blendMode)
        putNullable("effectName", effectName)
        putNullable("animationName", animationName)
        putNullable("uri", uri)
        putNullable("mimeType", mimeType)
        put("isImage", isImage)
        put("sourceDurationMs", sourceDurationMs)
        put("trimStartMs", trimStartMs)
    }

    private fun JSONObject.toTrack(): TimelineTrack {
        val clipsJson = optJSONArray("clips") ?: JSONArray()
        val clips = buildList {
            for (index in 0 until clipsJson.length()) {
                val clipJson = clipsJson.optJSONObject(index) ?: continue
                add(clipJson.toClip())
            }
        }
        return TimelineTrack(
            id = optString("id"),
            name = optString("name"),
            type = enumValueOrDefault(optString("type"), LayerType.VIDEO),
            isMuted = optBoolean("isMuted", false),
            isLocked = optBoolean("isLocked", false),
            clips = clips
        )
    }

    private fun JSONObject.toClip() = TimelineClip(
        id = optString("id"),
        trackIndex = optInt("trackIndex", 0),
        name = optString("name"),
        type = enumValueOrDefault(optString("type"), LayerType.VIDEO),
        startMs = optLong("startMs", 0L),
        durationMs = optLong("durationMs", 0L),
        color = optLong("color", 0L),
        drawableResId = if (isNull("drawableResId")) null else optInt("drawableResId"),
        text = nullableString("text"),
        textColor = optLong("textColor", 0xFFFFFFFF),
        shapePattern = nullableString("shapePattern"),
        volume = optDouble("volume", 1.0).toFloat(),
        speed = optDouble("speed", 1.0).toFloat(),
        isMuted = optBoolean("isMuted", false),
        isLocked = optBoolean("isLocked", false),
        isFlippedH = optBoolean("isFlippedH", false),
        isFlippedV = optBoolean("isFlippedV", false),
        blendMode = optString("blendMode", "Normal"),
        effectName = nullableString("effectName"),
        animationName = nullableString("animationName"),
        uri = nullableString("uri"),
        mimeType = nullableString("mimeType"),
        isImage = optBoolean("isImage", false),
        sourceDurationMs = optLong("sourceDurationMs", 0L),
        trimStartMs = optLong("trimStartMs", 0L)
    )

    private fun JSONObject.putNullable(key: String, value: Any?) {
        put(key, value ?: JSONObject.NULL)
    }

    private fun JSONObject.nullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key)

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String, default: T): T =
        runCatching { enumValueOf<T>(value) }.getOrDefault(default)

    private companion object {
        const val FILE_NAME = "tracket_project.json"
        const val SCHEMA_VERSION = 1
    }
}
