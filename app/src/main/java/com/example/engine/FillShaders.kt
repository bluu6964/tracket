package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.example.model.GRAD_RADIAL
import com.example.model.GRAD_SWEEP
import com.example.model.IMG_FIT
import com.example.model.IMG_STRETCH
import com.example.model.TimelineClip
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Shaders for gradient and picture fills. They are built in the layer's own box
 * (0..w, 0..h), so the preview and the exporter draw exactly the same thing.
 */
fun buildGradientShader(clip: TimelineClip, w: Float, h: Float, ox: Float = 0f, oy: Float = 0f): Shader {
    val c1 = clip.gradColor1.toInt()
    val c2 = clip.gradColor2.toInt()
    val x1 = ox + clip.gradX1 * w
    val y1 = oy + clip.gradY1 * h
    val x2 = ox + clip.gradX2 * w
    val y2 = oy + clip.gradY2 * h
    return when (clip.gradType) {
        GRAD_RADIAL -> RadialGradient(
            x1, y1, hypot(x2 - x1, y2 - y1).coerceAtLeast(1f), c1, c2, Shader.TileMode.CLAMP
        )
        GRAD_SWEEP -> SweepGradient(x1, y1, intArrayOf(c1, c2, c1), floatArrayOf(0f, 0.5f, 1f)).also {
            val deg = Math.toDegrees(atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())).toFloat()
            it.setLocalMatrix(Matrix().apply { postRotate(deg, x1, y1) })
        }
        else -> {
            // Zero length would make an invalid gradient: nudge the end point.
            val ex = if (x1 == x2 && y1 == y2) x2 + 1f else x2
            LinearGradient(x1, y1, ex, y2, c1, c2, Shader.TileMode.CLAMP)
        }
    }
}

private val shaderBitmaps = object : LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) = size > 6
}

/** Picture laid out inside a w x h box as FILL (cover) / FIT (contain) / STRETCH. */
fun buildImageShader(src: Bitmap, uriKey: String, mode: Int, w: Int, h: Int, ox: Float = 0f, oy: Float = 0f): Shader {
    val bw = w.coerceIn(1, 2048)
    val bh = h.coerceIn(1, 2048)
    val key = "$uriKey|$mode|$bw|$bh"
    val bmp = synchronized(shaderBitmaps) {
        shaderBitmaps[key] ?: run {
            val out = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val sw = src.width.toFloat()
            val sh = src.height.toFloat()
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
            when (mode) {
                IMG_STRETCH -> canvas.drawBitmap(src, null, RectF(0f, 0f, bw.toFloat(), bh.toFloat()), paint)
                IMG_FIT -> {
                    val k = minOf(bw / sw, bh / sh)
                    val dw = sw * k
                    val dh = sh * k
                    val l = (bw - dw) / 2f
                    val t = (bh - dh) / 2f
                    canvas.drawBitmap(src, null, RectF(l, t, l + dw, t + dh), paint)
                }
                else -> { // FILL: cover the whole box, crop the rest
                    val k = maxOf(bw / sw, bh / sh)
                    val cropW = bw / k
                    val cropH = bh / k
                    val l = ((sw - cropW) / 2f).toInt()
                    val t = ((sh - cropH) / 2f).toInt()
                    canvas.drawBitmap(
                        src,
                        Rect(l, t, (l + cropW).toInt().coerceAtMost(src.width), (t + cropH).toInt().coerceAtMost(src.height)),
                        RectF(0f, 0f, bw.toFloat(), bh.toFloat()),
                        paint
                    )
                }
            }
            shaderBitmaps[key] = out
            out
        }
    }
    return BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also {
        if (ox != 0f || oy != 0f) it.setLocalMatrix(Matrix().apply { postTranslate(ox, oy) })
    }
}

/** Decoded pictures used as layer fills (small, shared by preview and export). */
object FillImages {
    /** Bumped when a picture finishes loading, so the preview redraws. */
    var version by mutableIntStateOf(0)
        private set

    private val cache = ConcurrentHashMap<String, Bitmap>()
    private val busy = ConcurrentHashMap.newKeySet<String>()
    private val failed = ConcurrentHashMap.newKeySet<String>()
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** Returns the picture if it is ready, otherwise starts loading it and returns null. */
    fun peek(uri: String): Bitmap? {
        version // read so a finished load redraws the caller
        cache[uri]?.let { return it }
        val ctx = appContext ?: return null
        if (uri in failed || !busy.add(uri)) return null
        Thread {
            val b = decode(ctx, uri)
            if (b != null) cache[uri] = b else failed.add(uri)
            busy.remove(uri)
            Handler(Looper.getMainLooper()).post { version++ }
        }.start()
        return null
    }

    /** For the exporter (already on a background thread). */
    fun loadSync(uri: String): Bitmap? {
        cache[uri]?.let { return it }
        val ctx = appContext ?: return null
        return decode(ctx, uri)?.also { cache[uri] = it }
    }

    private fun decode(ctx: Context, uri: String): Bitmap? = runCatching {
        val u = android.net.Uri.parse(uri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        ctx.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }.getOrNull() ?: posterFrame(ctx, uri)

    /**
     * A video fill's poster (build 104): when the uri names a video rather than a picture, every
     * bitmap path (the Canvas painter, pose / repeat fallbacks, the legacy exporter) shows its
     * first frame, so the shape is never blank where the live composite does not run. The live
     * engines prefer the playing video; this is only the still underneath.
     */
    private fun posterFrame(ctx: Context, uri: String): Bitmap? = runCatching {
        val mmr = android.media.MediaMetadataRetriever()
        try {
            mmr.setDataSource(ctx, android.net.Uri.parse(uri))
            val frame = mmr.getFrameAtTime(0L, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return@runCatching null
            val longest = maxOf(frame.width, frame.height)
            if (longest <= 1600) frame
            else Bitmap.createScaledBitmap(frame, frame.width * 1600 / longest, frame.height * 1600 / longest, true)
        } finally {
            runCatching { mmr.release() }
        }
    }.getOrNull()
}
