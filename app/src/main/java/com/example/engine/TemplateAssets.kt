package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.model.CarouselSpec
import java.io.File

/**
 * Pictures the templates ship with, drawn at run time (build 142) — nothing is bundled in the
 * APK. The carousel's background is the measured reference backdrop (vertical grey gradient +
 * 34 px grid, see [CarouselSpec]) rendered fresh; the 8 card pictures are neutral abstract
 * placeholders of our own (the reference video's card art is third-party work and is NOT
 * reused) — each is a slot the user replaces with their own photo.
 */
object TemplateAssets {

    /** Uris of one instantiated carousel: the background picture + the 8 card pictures. */
    data class CarouselAssets(val backgroundUri: String, val cardUris: List<String>)

    private const val BG_W = 1440
    private const val BG_H = 900
    private const val CARD_PX = 288

    private fun lift(argb: Long, by: Int): Int {
        val r = ((argb shr 16) and 0xFF).toInt()
        val g = ((argb shr 8) and 0xFF).toInt()
        val b = (argb and 0xFF).toInt()
        return Color.rgb(
            (r + by).coerceAtMost(255), (g + by).coerceAtMost(255), (b + by).coerceAtMost(255)
        )
    }

    private fun gradientAt(y: Float): Int {
        val f = (y / BG_H).coerceIn(0f, 1f)
        fun ch(shift: Int): Int {
            val t = (CarouselSpec.BG_TOP shr shift) and 0xFF
            val b = (CarouselSpec.BG_BOTTOM shr shift) and 0xFF
            return (t + (b - t) * f).toInt()
        }
        return Color.rgb(ch(16), ch(8), ch(0))
    }

    /** The template background at 2x scene resolution: gradient + grid, drawn from the spec. */
    fun backgroundBitmap(): Bitmap {
        val bmp = Bitmap.createBitmap(BG_W, BG_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val grad = LinearGradient(
            0f, 0f, 0f, BG_H.toFloat(),
            CarouselSpec.BG_TOP.toInt(), CarouselSpec.BG_BOTTOM.toInt(), Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, BG_W.toFloat(), BG_H.toFloat(), Paint().apply { shader = grad })
        val s = BG_W / CarouselSpec.SCENE_W  // scene px -> bitmap px (2x)
        val lineW = 1.5f * s
        val paint = Paint().apply { style = Paint.Style.FILL }
        // Vertical lines (x = 19 mod 34 scene px): the +8 lift follows the gradient, so the
        // line is drawn in short vertical chunks, each lifted from the fill at its own height.
        var lx = CarouselSpec.GRID_OFF_X
        while (lx < CarouselSpec.SCENE_W) {
            var y = 0f
            while (y < CarouselSpec.SCENE_H) {
                val chunk = 45f
                paint.color = lift(
                    (0xFF000000L or (gradientAt((y + chunk / 2f) * s).toLong() and 0xFFFFFFL)),
                    CarouselSpec.GRID_LIFT
                )
                canvas.drawRect(lx * s - lineW / 2f, y * s, lx * s + lineW / 2f, (y + chunk) * s, paint)
                y += chunk
            }
            lx += CarouselSpec.GRID_STEP
        }
        // Horizontal lines (y = 18 mod 34 scene px): constant height = one lifted colour each.
        var ly = CarouselSpec.GRID_OFF_Y
        while (ly < CarouselSpec.SCENE_H) {
            paint.color = lift(
                (0xFF000000L or (gradientAt(ly * s).toLong() and 0xFFFFFFL)),
                CarouselSpec.GRID_LIFT
            )
            canvas.drawRect(0f, ly * s - lineW / 2f, BG_W.toFloat(), ly * s + lineW / 2f, paint)
            ly += CarouselSpec.GRID_STEP
        }
        return bmp
    }

    /** Placeholder picture bases: 8 two-colour pairs of our own (index-matched to the cards). */
    private val PLACEHOLDER_PAIRS: List<Pair<Int, Int>> = listOf(
        0xFFEF9A9A.toInt() to 0xFFC62828.toInt(),
        0xFFFFCC80.toInt() to 0xFFEF6C00.toInt(),
        0xFFFFF59D.toInt() to 0xFFF9A825.toInt(),
        0xFFA5D6A7.toInt() to 0xFF2E7D32.toInt(),
        0xFF90CAF9.toInt() to 0xFF1565C0.toInt(),
        0xFFCE93D8.toInt() to 0xFF6A1B9A.toInt(),
        0xFFF48FB1.toInt() to 0xFFAD1457.toInt(),
        0xFF80CBC4.toInt() to 0xFF00695C.toInt()
    )

    /**
     * One neutral placeholder card picture: a diagonal two-colour wash + one big soft white
     * geometric motif (ring, triangle, bars, diamond, semicircle, plus, dots, wave by index).
     * Abstract on purpose — it marks a photo slot, it is not artwork to keep.
     */
    fun placeholderBitmap(index: Int): Bitmap {
        val bmp = Bitmap.createBitmap(CARD_PX, CARD_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val (c1, c2) = PLACEHOLDER_PAIRS[index % PLACEHOLDER_PAIRS.size]
        val grad = LinearGradient(
            0f, 0f, CARD_PX.toFloat(), CARD_PX.toFloat(), c1, c2, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, CARD_PX.toFloat(), CARD_PX.toFloat(), Paint().apply { shader = grad })
        val w = Paint().apply {
            color = Color.WHITE
            alpha = 150
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        val cx = CARD_PX / 2f
        val cy = CARD_PX / 2f
        val r = CARD_PX * 0.26f
        when (index % 8) {
            0 -> canvas.drawPath(Path().apply {
                addCircle(cx, cy, r, Path.Direction.CW)
                addCircle(cx, cy, r * 0.55f, Path.Direction.CCW)
            }, w)
            1 -> canvas.drawPath(Path().apply {
                moveTo(cx, cy - r); lineTo(cx + r, cy + r * 0.8f); lineTo(cx - r, cy + r * 0.8f); close()
            }, w)
            2 -> for (i in -1..1) {
                canvas.drawRect(cx + i * r * 0.75f - r * 0.22f, cy - r, cx + i * r * 0.75f + r * 0.22f, cy + r, w)
            }
            3 -> canvas.drawPath(Path().apply {
                moveTo(cx, cy - r); lineTo(cx + r, cy); lineTo(cx, cy + r); lineTo(cx - r, cy); close()
            }, w)
            4 -> canvas.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), 180f, 180f, true, w)
            5 -> {
                canvas.drawRect(cx - r * 0.28f, cy - r, cx + r * 0.28f, cy + r, w)
                canvas.drawRect(cx - r, cy - r * 0.28f, cx + r, cy + r * 0.28f, w)
            }
            6 -> for (dy in -1..1) for (dx in -1..1) {
                canvas.drawCircle(cx + dx * r * 0.72f, cy + dy * r * 0.72f, r * 0.2f, w)
            }
            else -> {
                val path = Path().apply {
                    moveTo(cx - r, cy)
                    cubicTo(cx - r * 0.4f, cy - r, cx + r * 0.4f, cy + r, cx + r, cy)
                    lineTo(cx + r, cy + r * 0.35f)
                    cubicTo(cx + r * 0.4f, cy + r * 1.35f, cx - r * 0.4f, cy + r * 0.65f, cx - r, cy + r * 0.35f)
                    close()
                }
                canvas.drawPath(path, w)
            }
        }
        return bmp
    }

    /**
     * Generates the carousel's pictures into the app's own import storage (the same place the
     * XML import flow copies media to) and returns their uris, or null when a write fails.
     */
    fun carouselUris(context: Context): CarouselAssets? = runCatching {
        val bg = saveToImports(context, backgroundBitmap(), "carousel_bg.png") ?: return null
        val cards = (0 until CarouselSpec.CARD_COUNT).map { i ->
            saveToImports(context, placeholderBitmap(i), "carousel_card_${i + 1}.png") ?: return null
        }
        CarouselAssets(bg, cards)
    }.getOrNull()

    /** Writes [bmp] as a PNG under filesDir/imports and returns its shareable uri (like
     *  [MediaImporter.copyToImports]' end state, without the copy through the resolver). */
    private fun saveToImports(context: Context, bmp: Bitmap, name: String): String? = runCatching {
        val dir = File(context.filesDir, "imports").apply { mkdirs() }
        val dest = File(dir, "tpl_${System.currentTimeMillis()}_$name")
        dest.outputStream().use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
        if (dest.length() == 0L) {
            dest.delete()
            return@runCatching null
        }
        try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", dest).toString()
        } catch (e: Exception) {
            Uri.fromFile(dest).toString()
        }
    }.getOrNull()
}
