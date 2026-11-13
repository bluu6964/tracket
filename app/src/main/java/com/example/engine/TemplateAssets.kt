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
 * reused) — each is a slot the user replaces with their own photo. The Logo Reveal's
 * placeholder is likewise our own abstract emblem ([logoBitmap]).
 */
object TemplateAssets {

    /** Uris of one instantiated carousel: the background picture + the 8 card pictures. */
    data class CarouselAssets(val backgroundUri: String, val cardUris: List<String>)

    /** Uris of one instantiated Genie template: the 6 bar avatar pictures + the 4 card photos. */
    data class GenieAssets(val avatarUris: List<String>, val photoUris: List<String>)

    private const val BG_W = 1440
    private const val BG_H = 900
    private const val CARD_PX = 288
    private const val LOGO_PX = 512
    private const val PORTRAIT_W = 360
    private const val PORTRAIT_H = 504

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
     * The Logo Reveal template's placeholder mark: a deep indigo → violet wash with one
     * bold white emblem of our own (a ring around a diamond, over a soft glow disc).
     * Abstract on purpose — it marks the logo slot the user replaces with their real logo
     * (Color → Fill → Image); it is not a brand and not artwork to keep.
     */
    fun logoBitmap(): Bitmap {
        val bmp = Bitmap.createBitmap(LOGO_PX, LOGO_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val grad = LinearGradient(
            0f, 0f, LOGO_PX.toFloat(), LOGO_PX.toFloat(),
            0xFF3949AB.toInt(), 0xFF6A1B9A.toInt(), Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, LOGO_PX.toFloat(), LOGO_PX.toFloat(), Paint().apply { shader = grad })
        val cx = LOGO_PX / 2f
        val cy = LOGO_PX / 2f
        // Soft glow disc under the emblem.
        canvas.drawCircle(cx, cy, LOGO_PX * 0.36f, Paint().apply {
            color = Color.WHITE
            alpha = 36
            style = Paint.Style.FILL
            isAntiAlias = true
        })
        // The ring.
        canvas.drawCircle(cx, cy, LOGO_PX * 0.27f, Paint().apply {
            color = Color.WHITE
            alpha = 235
            style = Paint.Style.STROKE
            strokeWidth = LOGO_PX * 0.055f
            isAntiAlias = true
        })
        // The diamond core.
        val d = LOGO_PX * 0.115f
        canvas.drawPath(Path().apply {
            moveTo(cx, cy - d); lineTo(cx + d, cy); lineTo(cx, cy + d); lineTo(cx - d, cy); close()
        }, Paint().apply {
            color = Color.WHITE
            alpha = 235
            style = Paint.Style.FILL
            isAntiAlias = true
        })
        return bmp
    }

    /**
     * Generates the carousel's pictures into the app's own import storage (the same place the
     * XML import flow copies media to) and returns their uris, or null when a write fails.
     * [cardCount] is the insert's chosen card count (build 154; the default stays the
     * reference's 8).
     */
    fun carouselUris(context: Context, cardCount: Int = CarouselSpec.CARD_COUNT): CarouselAssets? = runCatching {
        val bg = saveToImports(context, backgroundBitmap(), "carousel_bg.png") ?: return null
        val cards = (0 until cardCount).map { i ->
            saveToImports(context, placeholderBitmap(i), "carousel_card_${i + 1}.png") ?: return null
        }
        CarouselAssets(bg, cards)
    }.getOrNull()

    /** Generates the Logo Reveal placeholder into import storage; its uri, or null on failure. */
    fun logoUri(context: Context): String? = runCatching {
        saveToImports(context, logoBitmap(), "logo_reveal.png")
    }.getOrNull()

    /**
     * One portrait placeholder (the Genie cards' photo slots): a warm diagonal wash
     * with a soft white sun-disc and horizon arcs — an abstract stand-in for a
     * person's photo, drawn by us; the reference video's photos are NOT reused.
     * The user replaces it via Color -> Fill -> Image.
     */
    fun portraitBitmap(index: Int): Bitmap {
        val bmp = Bitmap.createBitmap(PORTRAIT_W, PORTRAIT_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val pairs = listOf(
            0xFFF6C177.toInt() to 0xFFB3541E.toInt(),
            0xFF9CCFD8.toInt() to 0xFF286983.toInt(),
            0xFFF2CDCD.toInt() to 0xFFA15C7B.toInt(),
            0xFFB5E48C.toInt() to 0xFF52796F.toInt()
        )
        val (c1, c2) = pairs[index % pairs.size]
        val grad = LinearGradient(
            0f, 0f, PORTRAIT_W.toFloat(), PORTRAIT_H.toFloat(), c1, c2, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, PORTRAIT_W.toFloat(), PORTRAIT_H.toFloat(), Paint().apply { shader = grad })
        val w = Paint().apply {
            color = Color.WHITE
            alpha = 120
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        // A soft sun disc + two horizon arcs.
        canvas.drawCircle(PORTRAIT_W * 0.68f, PORTRAIT_H * 0.30f, PORTRAIT_W * 0.17f, w)
        val arc = Paint().apply {
            color = Color.WHITE
            alpha = 90
            style = Paint.Style.STROKE
            strokeWidth = PORTRAIT_W * 0.03f
            isAntiAlias = true
        }
        canvas.drawArc(
            RectF(-PORTRAIT_W * 0.3f, PORTRAIT_H * 0.62f, PORTRAIT_W * 0.9f, PORTRAIT_H * 1.05f),
            200f, 140f, false, arc
        )
        canvas.drawArc(
            RectF(PORTRAIT_W * 0.35f, PORTRAIT_H * 0.72f, PORTRAIT_W * 1.35f, PORTRAIT_H * 1.15f),
            200f, 140f, false, arc
        )
        return bmp
    }

    /** Generates the Genie template's pictures (6 avatars + 4 card photos); null on failure. */
    fun genieUris(context: Context): GenieAssets? = runCatching {
        val avatars = (0 until 6).map { i ->
            saveToImports(context, placeholderBitmap(i), "genie_avatar_${i + 1}.png") ?: return null
        }
        val photos = (0 until 4).map { i ->
            saveToImports(context, portraitBitmap(i), "genie_photo_${i + 1}.png") ?: return null
        }
        GenieAssets(avatars, photos)
    }.getOrNull()

    /** Generates the Social Growth template's profile-picture placeholder; null on failure. */
    fun growthAvatarUri(context: Context): String? = runCatching {
        saveToImports(context, placeholderBitmap(3), "growth_avatar.png")
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
