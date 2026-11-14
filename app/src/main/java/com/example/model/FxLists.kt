package com.example.model

/**
 * The Effects browser's per-user lists (build 200 — the layout redesign of Sarowar's
 * reference video): **Favorites** (bookmarked effect ids) and **Recent**
 * (most-recently-applied first). Both persist as ONE comma-joined string in the
 * editor SharedPreferences (keys `fx_favorites` / `fx_recent`); effect ids are
 * `[a-z0-9_]` by construction, so the comma format cannot collide. The laws here
 * are pure so the harness pins them; the UI only (de)serialises.
 */
object FxLists {

    /** Recent keeps at most this many ids; older applications fall off the end. */
    const val RECENT_CAP = 24

    /** Stored string -> id list: blanks dropped, duplicates removed (first wins). */
    fun parse(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val out = mutableListOf<String>()
        for (part in raw.split(',')) {
            val id = part.trim()
            if (id.isNotEmpty() && id !in out) out += id
        }
        return out
    }

    /** Id list -> stored string (the exact inverse of [parse] for clean lists). */
    fun serialize(ids: List<String>): String = ids.joinToString(",")

    /** Bookmark toggle: an absent id is appended, a present one removed. */
    fun toggleFavorite(favorites: List<String>, id: String): List<String> =
        if (id in favorites) favorites - id else favorites + id

    /** Records an application: [id] moves to the front, deduped, capped at [cap]. */
    fun pushRecent(recent: List<String>, id: String, cap: Int = RECENT_CAP): List<String> =
        (listOf(id) + recent.filter { it != id }).take(cap.coerceAtLeast(1))

    /** The Favorites chip's content: the library defs whose ids are bookmarked, in LIBRARY order. */
    fun favoriteDefs(all: List<FxDef>, favorites: List<String>): List<FxDef> {
        val favs = favorites.toSet()
        return all.filter { it.id in favs }
    }

    /** The Recent chip's content: defs in RECENT order; unknown ids (removed effects) drop out. */
    fun recentDefs(all: List<FxDef>, recent: List<String>): List<FxDef> {
        val byId = all.associateBy { it.id }
        return recent.mapNotNull { byId[it] }
    }
}

/**
 * Deterministic per-effect variation for the browser's procedural tile artwork
 * (build 200): the tiles are drawn in Compose (nebula fields + stars + the
 * category motif), and two effects of one category must still read as different
 * tiles. Everything derives from a stable FNV-1a hash of the effect id — same
 * id, same tile, on every device and launch; no stored state, no assets.
 */
object FxTileArt {

    /** Stable 32-bit FNV-1a hash of the effect id. */
    fun seed(id: String): Int {
        var h = 0x811C9DC5.toInt() // the FNV offset basis, bit pattern kept through the truncation
        for (c in id) {
            h = h xor c.code
            h *= 0x01000193 // the FNV prime; Int overflow is the algorithm
        }
        return h
    }

    /** A deterministic pseudo-random value in [0, 1) for ([seed], [salt]) — a xorshift of the two. */
    fun unit(seed: Int, salt: Int): Float {
        var x = seed xor (salt * -1640531527) // salt spread by the golden-ratio constant
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        return (x and 0xFFFFFF) / 16777216f // top 24 bits -> [0, 1)
    }

    /** The tile's hue rotation within its category family, in degrees: [-24, +24). */
    fun hueShiftDeg(id: String): Float = unit(seed(id), 77) * 48f - 24f
}
