package com.example.ui.text

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** One selectable font in the Fonts tab. */
data class FontEntry(val name: String, val category: String)

/**
 * Font list shown in the Text edit > Fonts tab.
 * "System" fonts ship with Android and work offline. Everything else is a Google Font that is
 * downloaded once (when first shown or used) and cached in the app's private storage.
 */
object FontCatalog {
    const val DEFAULT_FONT = "Roboto"
    const val USER_CATEGORY = "My fonts"

    val system = listOf(
        FontEntry("Roboto", "System"),
        FontEntry("Serif", "System"),
        FontEntry("Monospace", "System"),
        FontEntry("Cursive", "System")
    )

    val google: List<FontEntry> = buildList {
        fun add(category: String, vararg names: String) = names.forEach { add(FontEntry(it, category)) }
        add("Display", "Lobster", "Alfa Slab One", "Righteous", "Lilita One", "Abril Fatface",
            "Oleo Script", "Rowdies", "Comfortaa", "Passion One", "Bangers", "Bungee",
            "Black Ops One", "Press Start 2P")
        add("Sans Serif", "Poppins", "Montserrat", "Oswald", "Raleway", "Nunito", "Open Sans",
            "Inter", "Bebas Neue", "Anton")
        add("Serif", "Playfair Display", "Merriweather", "Lora", "Cinzel", "Cormorant Garamond")
        add("Handwriting", "Pacifico", "Caveat", "Dancing Script", "Satisfy", "Great Vibes",
            "Permanent Marker", "Shadows Into Light")
        add("Mono", "Roboto Mono", "Source Code Pro", "Space Mono")
        add("Bangla", "Hind Siliguri", "Noto Sans Bengali", "Noto Serif Bengali", "Baloo Da 2",
            "Galada", "Atma", "Mina", "Anek Bangla", "Tiro Bangla")
    }

    val all: List<FontEntry> get() = system + google
}

/**
 * Loads fonts for Compose. Resolution order: Android built-ins, then a cached file
 * (downloaded Google Font or a font the user added), then a one-time download.
 */
@OptIn(ExperimentalTextApi::class)
object FontRepository {

    private val families = ConcurrentHashMap<String, FontFamily>()
    private val failed = ConcurrentHashMap.newKeySet<String>()

    private const val PREFS = "tracket_fonts"
    private const val KEY_USER_FONTS = "user_fonts"

    private fun dir(context: Context): File =
        File(context.filesDir, "fonts").apply { mkdirs() }

    private fun slug(name: String) = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')

    /** Where a downloaded Google Font goes (Google serves TTF). */
    private fun fileFor(context: Context, name: String) = File(dir(context), "${slug(name)}.ttf")

    // Font file extensions the store recognises (build 201, audit S-6: until
    // 200 EVERY stored font was named .ttf — an imported .otf kept .otf bytes
    // under a .ttf name).
    private val FONT_EXTS = listOf("ttf", "otf", "ttc", "otc")

    /** The stored file of font [name] under ANY real extension, or null. */
    private fun storedFile(context: Context, name: String): File? {
        val base = slug(name)
        val d = dir(context)
        return FONT_EXTS.firstNotNullOfOrNull { ext ->
            File(d, "$base.$ext").takeIf { it.exists() && it.length() > 0 }
        }
    }

    private fun builtIn(name: String): FontFamily? = when (name) {
        "Roboto", "System", "" -> FontFamily.Default
        "Serif" -> FontFamily.Serif
        "Monospace" -> FontFamily.Monospace
        "Cursive" -> FontFamily.Cursive
        else -> null
    }

    /** Returns the family right now if it needs no download (built-in or already cached). */
    fun resolveNow(context: Context, name: String): FontFamily? {
        builtIn(name)?.let { return it }
        families[name]?.let { return it }
        val f = storedFile(context, name)
        if (f != null) {
            return runCatching { FontFamily(Font(f, FontWeight.Normal)) }
                .getOrNull()?.also { families[name] = it }
        }
        return null
    }

    /**
     * The font file of a downloaded Google font or a font the user added, or null (built-in fonts have no file
     * of their own). The C++ text engine of the export loads this file directly.
     */
    fun fileOrNull(context: Context, name: String): File? {
        if (builtIn(name) != null) return null
        return storedFile(context, name)
    }

    fun needsDownload(context: Context, name: String): Boolean =
        resolveNow(context, name) == null && name !in failed && name !in userFonts(context)

    /** Downloads a Google Font (regular style). Returns null when offline or unknown. */
    suspend fun download(context: Context, name: String): FontFamily? = withContext(Dispatchers.IO) {
        resolveNow(context, name)?.let { return@withContext it }
        if (name in failed) return@withContext null
        val target = fileFor(context, name)
        val ok = runCatching {
            val q = java.net.URLEncoder.encode(name, "UTF-8")
            val css = httpGet("https://fonts.googleapis.com/css?family=$q").toString(Charsets.UTF_8)
            val url = Regex("url\\((https://[^)]+)\\)").find(css)?.groupValues?.get(1)
                ?: error("font not found")
            val bytes = httpGet(url)
            val tmp = File(target.parentFile, target.name + ".part")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
        }.isSuccess
        if (!ok) { failed.add(name); return@withContext null }
        resolveNow(context, name)
    }

    private fun httpGet(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 15000
        try {
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    // ---- Fonts added by the user ("Add font") ---------------------------------------------

    fun userFonts(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_USER_FONTS, "").orEmpty()
            .split('\n').filter { it.isNotBlank() }

    /** Copies a picked font file into app storage and remembers it. Returns the font's name. */
    suspend fun importUserFont(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val display = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && i >= 0) c.getString(i) else null
            } ?: "My font"
            val baseName = display.substringBeforeLast('.').replace('_', ' ').replace('-', ' ').trim()
                .ifBlank { "My font" }
            // Build 201 (S-6): keep the font's REAL extension — from the file
            // name, else the content type, else ttf.
            val ext = display.substringAfterLast('.', "").lowercase().takeIf { it in FONT_EXTS }
                ?: when (context.contentResolver.getType(uri)?.lowercase()) {
                    "font/otf", "application/x-font-otf" -> "otf"
                    "font/collection", "application/x-font-ttc" -> "ttc"
                    else -> "ttf"
                }
            // De-duplicate instead of silently overwriting (S-6): a name that
            // is already registered, or whose slug already has a stored file
            // (e.g. a downloaded Google font of the same name), gets the next
            // free "Name 2", "Name 3", ... — both fonts stay usable.
            val registered = userFonts(context)
            var name = baseName
            var n = 2
            while (name in registered || storedFile(context, name) != null) {
                name = "$baseName ${n++}"
            }
            val target = File(dir(context), "${slug(name)}.$ext")
            context.contentResolver.openInputStream(uri)!!.use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
            // Reject files Android cannot read as a font.
            try {
                android.graphics.Typeface.createFromFile(target)
            } catch (e: Exception) {
                target.delete()
                throw e
            }
            families.remove(name)
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val list = (registered + name).distinct()
            prefs.edit().putString(KEY_USER_FONTS, list.joinToString("\n")).apply()
            name
        }.getOrNull()
    }
}

/** Compose helper: gives the font right away if it is available, downloads it otherwise. */
@Composable
fun rememberFontFamily(name: String): FontFamily {
    val context = LocalContext.current.applicationContext
    val state by produceState<FontFamily?>(
        initialValue = FontRepository.resolveNow(context, name),
        key1 = name
    ) {
        val now = FontRepository.resolveNow(context, name)
        if (now != null) {
            value = now
        } else if (FontRepository.needsDownload(context, name)) {
            value = FontRepository.download(context, name)
        }
    }
    return state ?: FontFamily.Default
}
