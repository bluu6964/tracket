package com.example.engine

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import org.json.JSONObject

/**
 * Stickers shipped in the app: app/src/main/assets/stickers/<category>/...
 *
 *  - static sticker  = one PNG file            -> "asset:stickers/<category>/<name>.png"
 *  - animated sticker = a folder of frames      -> "asset:stickers/<category>/<name>_anim"
 *    (frame_000.png, frame_001.png, ... + meta.json {"fps": 12})
 *
 * Preview and export both ask [frame] for the same time, so they show the same picture.
 * Call [init] once (MainActivity) before use.
 */
object StickerLibrary {

    const val ROOT = "stickers"
    const val URI_PREFIX = "asset:"

    data class Item(
        /** Path inside assets, e.g. "stickers/love/heart.png" or "stickers/love/heart_pulse_anim". */
        val assetPath: String,
        val name: String,
        val animated: Boolean,
        val frameCount: Int,
        val fps: Float
    )

    @Volatile private var assets: AssetManager? = null
    private val cache = object : LruCache<String, Bitmap>(
        // ~1/16 of the app heap, in KB
        (Runtime.getRuntime().maxMemory() / 1024 / 16).toInt().coerceAtLeast(4 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private val infoCache = HashMap<String, Item>()

    fun init(context: Context) {
        assets = context.applicationContext.assets
    }

    private fun am(): AssetManager? = assets

    /** Category folder names, sorted. */
    fun categories(): List<String> {
        val a = am() ?: return emptyList()
        return (a.list(ROOT) ?: emptyArray()).filter { !it.contains('.') }.sorted()
    }

    /** Stickers of one category: static PNGs and animated frame folders. */
    fun items(category: String): List<Item> {
        val a = am() ?: return emptyList()
        val dir = "$ROOT/$category"
        val entries = (a.list(dir) ?: emptyArray()).sorted()
        return entries.mapNotNull { entry ->
            val path = "$dir/$entry"
            if (entry.endsWith(".png", ignoreCase = true)) {
                Item(path, entry.removeSuffix(".png"), animated = false, frameCount = 1, fps = 0f)
            } else {
                val frames = framesOf(path)
                if (frames.isEmpty()) null
                else {
                    val fps = readFps(path)
                    Item(path, entry, animated = true, frameCount = frames.size, fps = fps)
                }
            }
        }
    }

    /** Sticker info for a stored uri ("asset:stickers/...") or null. */
    fun info(uri: String?): Item? {
        val path = uri?.removePrefix(URI_PREFIX) ?: return null
        synchronized(infoCache) {
            infoCache[path]?.let { return it }
        }
        val a = am() ?: return null
        val item = if (path.endsWith(".png")) {
            Item(path, path.substringAfterLast('/').removeSuffix(".png"), false, 1, 0f)
        } else {
            val frames = framesOf(path)
            if (frames.isEmpty()) return null
            Item(path, path.substringAfterLast('/'), true, frames.size, readFps(path))
        }
        synchronized(infoCache) { infoCache[path] = item }
        return item
    }

    /** Frame index shown [elapsedMs] after the sticker's start (looping). */
    fun frameIndexAt(item: Item, elapsedMs: Long): Int {
        if (!item.animated || item.frameCount <= 1) return 0
        val fps = if (item.fps > 0f) item.fps else 12f
        val idx = ((elapsedMs.coerceAtLeast(0L) / 1000.0) * fps).toLong()
        return (idx % item.frameCount).toInt()
    }

    /** Decoded frame (cached). Null when the asset is missing. */
    fun frame(item: Item, index: Int): Bitmap? {
        val key = "${item.assetPath}#$index"
        cache.get(key)?.let { return it }
        val a = am() ?: return null
        val file = if (item.animated) {
            "${item.assetPath}/" + String.format("frame_%03d.png", index)
        } else {
            item.assetPath
        }
        val bmp = try {
            a.open(file).use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            null
        } ?: return null
        cache.put(key, bmp)
        return bmp
    }

    /** Frame to show for a stored uri at [elapsedMs] after the sticker starts. */
    fun frameFor(uri: String?, elapsedMs: Long): Bitmap? {
        val item = info(uri) ?: return null
        return frame(item, frameIndexAt(item, elapsedMs))
    }

    private fun framesOf(folder: String): List<String> {
        val a = am() ?: return emptyList()
        return (a.list(folder) ?: emptyArray())
            .filter { it.startsWith("frame_") && it.endsWith(".png") }
            .sorted()
    }

    private fun readFps(folder: String): Float {
        return try {
            val text = am()?.open("$folder/meta.json")?.bufferedReader()?.use { it.readText() } ?: return 12f
            JSONObject(text).optDouble("fps", 12.0).toFloat().coerceIn(1f, 60f)
        } catch (e: Exception) {
            12f
        }
    }
}
