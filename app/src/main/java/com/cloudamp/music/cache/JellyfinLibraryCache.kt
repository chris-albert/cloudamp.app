package com.cloudamp.music.cache

import android.content.Context
import android.content.SharedPreferences
import com.cloudamp.music.api.JellyfinItem
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class JellyfinLibraryCache(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val cacheDir: File get() = File(context.filesDir, "jellyfin_cache")
    private val albumsDir: File get() = File(cacheDir, "albums")
    private val tracksDir: File get() = File(cacheDir, "tracks")

    companion object {
        private const val PREFS_NAME = "jellyfin_library_cache"
        private const val KEY_ARTISTS = "cached_artists"
        private const val KEY_PLAYLISTS = "cached_playlists"
        private const val KEY_ARTIST_GROUPS = "cached_artist_groups"
        private const val KEY_LAST_LOADED = "last_loaded_timestamp"

        @Volatile
        private var instance: JellyfinLibraryCache? = null

        fun getInstance(context: Context): JellyfinLibraryCache {
            return instance ?: synchronized(this) {
                instance ?: JellyfinLibraryCache(context.applicationContext).also { instance = it }
            }
        }
    }

    fun saveArtists(artists: List<JellyfinItem>) {
        val json = gson.toJson(artists)
        prefs.edit().putString(KEY_ARTISTS, json).apply()
    }

    fun getArtists(): List<JellyfinItem>? {
        val json = prefs.getString(KEY_ARTISTS, null) ?: return null
        return try {
            val type = object : TypeToken<List<JellyfinItem>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            null
        }
    }

    fun savePlaylists(playlists: List<JellyfinItem>) {
        val json = gson.toJson(playlists)
        prefs.edit().putString(KEY_PLAYLISTS, json).apply()
    }

    fun getPlaylists(): List<JellyfinItem>? {
        val json = prefs.getString(KEY_PLAYLISTS, null) ?: return null
        return try {
            val type = object : TypeToken<List<JellyfinItem>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            null
        }
    }

    // ── File-based album/track storage ─────────────────────────────────

    fun saveArtistAlbums(artistId: String, albums: List<JellyfinItem>) {
        albumsDir.mkdirs()
        val file = File(albumsDir, "$artistId.json")
        file.writeText(gson.toJson(albums))
    }

    fun getArtistAlbums(artistId: String): List<JellyfinItem>? {
        val file = File(albumsDir, "$artistId.json")
        if (!file.exists()) return null
        return try {
            val type = object : TypeToken<List<JellyfinItem>>() {}.type
            gson.fromJson(file.readText(), type)
        } catch (e: Exception) {
            null
        }
    }

    fun saveAlbumTracks(albumId: String, tracks: List<JellyfinItem>) {
        tracksDir.mkdirs()
        val file = File(tracksDir, "$albumId.json")
        file.writeText(gson.toJson(tracks))
    }

    fun getAlbumTracks(albumId: String): List<JellyfinItem>? {
        val file = File(tracksDir, "$albumId.json")
        if (!file.exists()) return null
        return try {
            val type = object : TypeToken<List<JellyfinItem>>() {}.type
            gson.fromJson(file.readText(), type)
        } catch (e: Exception) {
            null
        }
    }

    // ── Artist groups (representative ID → comma-separated group IDs) ──

    fun saveArtistGroups(groups: Map<String, List<String>>) {
        val serialized = groups.map { (repId, ids) -> "$repId=${ids.joinToString(",")}" }
            .joinToString("|")
        prefs.edit().putString(KEY_ARTIST_GROUPS, serialized).apply()
    }

    fun getArtistGroups(): Map<String, List<String>>? {
        val raw = prefs.getString(KEY_ARTIST_GROUPS, null) ?: return null
        if (raw.isEmpty()) return emptyMap()
        return try {
            raw.split("|").associate { entry ->
                val parts = entry.split("=", limit = 2)
                parts[0] to parts[1].split(",")
            }
        } catch (e: Exception) {
            null
        }
    }

    fun getGroupedArtistIds(artistId: String): List<String>? {
        val groups = getArtistGroups() ?: return null
        // Check if artistId is a representative ID
        groups[artistId]?.let { return it }
        // Check if artistId is a member of any group
        for ((repId, ids) in groups) {
            if (artistId in ids) return ids
        }
        return null
    }

    fun getRepresentativeArtistId(artistId: String): String? {
        val groups = getArtistGroups() ?: return null
        if (artistId in groups) return artistId
        for ((repId, ids) in groups) {
            if (artistId in ids) return repId
        }
        return null
    }

    // ── Cache completeness ─────────────────────────────────────────────

    fun hasFullCache(): Boolean {
        return hasCache() && albumsDir.exists() && (albumsDir.listFiles()?.isNotEmpty() == true)
    }

    fun markCacheComplete() {
        prefs.edit().putLong(KEY_LAST_LOADED, System.currentTimeMillis()).apply()
    }

    fun getLastLoadedTimestamp(): Long {
        return prefs.getLong(KEY_LAST_LOADED, 0)
    }

    fun getLastLoadedFormatted(): String? {
        val timestamp = getLastLoadedTimestamp()
        if (timestamp == 0L) return null

        val dateFormat = SimpleDateFormat("MMM d, yyyy 'at' h:mm a", Locale.getDefault())
        return dateFormat.format(Date(timestamp))
    }

    fun hasCache(): Boolean {
        return prefs.contains(KEY_ARTISTS) && getLastLoadedTimestamp() > 0
    }

    fun clearCache() {
        prefs.edit()
            .remove(KEY_ARTISTS)
            .remove(KEY_PLAYLISTS)
            .remove(KEY_ARTIST_GROUPS)
            .remove(KEY_LAST_LOADED)
            .apply()

        // Delete file-based cache
        albumsDir.deleteRecursively()
        tracksDir.deleteRecursively()
    }
}
