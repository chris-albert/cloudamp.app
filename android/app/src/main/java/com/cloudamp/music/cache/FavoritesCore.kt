package com.cloudamp.music.cache

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Pure parse/merge/serialize logic for the Favorites File
 * (.cloudamp/favorites.json) — no I/O, no Android dependencies.
 *
 * Mirrors web/src/lib/favorites-core.ts so both platforms read and write
 * the same snapshot:
 *   { "schemaVersion": 1, "favorites": [{ "albumId": "...", "favoritedAt": "..." }] }
 *
 * See docs/adr/0001-favorites-json-snapshot.md for the storage decision.
 */
object FavoritesCore {

    const val SCHEMA_VERSION = 1

    data class FavoriteEntry(val albumId: String, val favoritedAt: String)

    data class FavoritesFile(val schemaVersion: Int, val favorites: List<FavoriteEntry>)

    sealed class ParseResult {
        data class Ok(val file: FavoritesFile) : ParseResult()

        /** Unknown schemaVersion — callers must never overwrite the file. */
        data class UnknownVersion(val schemaVersion: String) : ParseResult()
    }

    fun emptyFile(): FavoritesFile = FavoritesFile(SCHEMA_VERSION, emptyList())

    /**
     * Empty, malformed, or non-object content bootstraps an empty file (the
     * first-write path). A schemaVersion other than [SCHEMA_VERSION] is
     * refused so a file written by a newer client is never destroyed.
     * Entries without a string albumId are dropped; a missing favoritedAt
     * is coerced to "".
     */
    fun parse(text: String): ParseResult {
        val root = try {
            JsonParser.parseString(text)
        } catch (e: Exception) {
            return ParseResult.Ok(emptyFile())
        }
        if (!root.isJsonObject) return ParseResult.Ok(emptyFile())
        val obj = root.asJsonObject

        val version = obj.get("schemaVersion")
        val isCurrentVersion = version != null && version.isJsonPrimitive &&
            version.asJsonPrimitive.isNumber && version.asJsonPrimitive.asDouble == SCHEMA_VERSION.toDouble()
        if (!isCurrentVersion) {
            return ParseResult.UnknownVersion(version?.toString() ?: "missing")
        }

        val rawList = obj.get("favorites")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
        val favorites = mutableListOf<FavoriteEntry>()
        for (element in rawList) {
            if (!element.isJsonObject) continue
            val entry = element.asJsonObject
            val albumId = entry.get("albumId")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString ?: continue
            val favoritedAt = entry.get("favoritedAt")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString ?: ""
            favorites.add(FavoriteEntry(albumId, favoritedAt))
        }
        return ParseResult.Ok(FavoritesFile(SCHEMA_VERSION, favorites))
    }

    /**
     * Apply a single toggle on top of a (freshly fetched) remote list.
     * Favoriting an already-favorited album keeps its original favoritedAt;
     * unfavoriting removes the entry. All other entries pass through
     * untouched, which is what preserves orphans.
     */
    fun applyToggle(
        file: FavoritesFile,
        albumId: String,
        favorite: Boolean,
        favoritedAt: String
    ): FavoritesFile {
        return if (favorite) {
            if (file.favorites.any { it.albumId == albumId }) file
            else file.copy(favorites = file.favorites + FavoriteEntry(albumId, favoritedAt))
        } else {
            file.copy(favorites = file.favorites.filter { it.albumId != albumId })
        }
    }

    fun isFavorite(file: FavoritesFile, albumId: String): Boolean =
        file.favorites.any { it.albumId == albumId }

    /**
     * Resolve favorite entries against the current library for display.
     * Entries whose albumId doesn't resolve are hidden — not removed — so they
     * reappear automatically when the album returns to the library (ADR-0001).
     * Most recently favorited first.
     */
    fun <A> resolveFavoriteAlbums(
        favorites: List<FavoriteEntry>,
        albumById: Map<String, A>
    ): List<A> =
        favorites
            .sortedByDescending { it.favoritedAt }
            .mapNotNull { albumById[it.albumId] }

    /**
     * Serialize the full loaded list — never a view-filtered one — with
     * exactly the documented fields, 2-space pretty-printed like the web
     * client's JSON.stringify(obj, null, 2).
     */
    fun serialize(file: FavoritesFile): String {
        val obj = JsonObject().apply {
            addProperty("schemaVersion", file.schemaVersion)
            add("favorites", JsonArray().apply {
                for (entry in file.favorites) {
                    add(JsonObject().apply {
                        addProperty("albumId", entry.albumId)
                        addProperty("favoritedAt", entry.favoritedAt)
                    })
                }
            })
        }
        return GsonBuilder().setPrettyPrinting().create().toJson(obj)
    }
}
