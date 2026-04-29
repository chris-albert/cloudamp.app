package com.cloudamp.music.cache

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.cloudamp.music.api.GDriveAlbum
import com.cloudamp.music.api.GDriveArtist
import com.cloudamp.music.api.GDriveTrack
import com.cloudamp.music.playback.GDriveImageProvider
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class GDriveLibraryCache(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val cacheDir: File get() = File(context.filesDir, "gdrive_cache")
    private val albumsDir: File get() = File(cacheDir, "albums")
    private val tracksDir: File get() = File(cacheDir, "tracks")

    companion object {
        private const val TAG = "GDriveLibraryCache"
        private const val PREFS_NAME = "gdrive_library_cache"
        private const val KEY_ARTISTS = "cached_artists"
        private const val KEY_LAST_LOADED = "last_loaded_timestamp"
        private const val KEY_ROOT_FOLDER_ID = "gdrive_music_root_id"
        private const val KEY_ROOT_FOLDER_NAME = "gdrive_music_root_name"
        private const val KEY_RECENTLY_PLAYED = "recently_played_album_ids"
        private const val KEY_STAT_ARTISTS = "stat_artists"
        private const val KEY_STAT_ALBUMS = "stat_albums"
        private const val KEY_STAT_TRACKS = "stat_tracks"
        private const val KEY_STAT_TOTAL_COVERS = "stat_total_covers"
        private const val MAX_RECENTLY_PLAYED = 20

        @Volatile
        private var instance: GDriveLibraryCache? = null

        fun getInstance(context: Context): GDriveLibraryCache {
            return instance ?: synchronized(this) {
                instance ?: GDriveLibraryCache(context.applicationContext).also { instance = it }
            }
        }
    }

    // ── Root folder config ────────────────────────────────────────────

    fun getRootFolderId(): String? = prefs.getString(KEY_ROOT_FOLDER_ID, null)

    fun getRootFolderName(): String? = prefs.getString(KEY_ROOT_FOLDER_NAME, null)

    fun setRootFolder(folderId: String, folderName: String) {
        prefs.edit()
            .putString(KEY_ROOT_FOLDER_ID, folderId)
            .putString(KEY_ROOT_FOLDER_NAME, folderName)
            .apply()
    }

    // ── Artist storage ────────────────────────────────────────────────

    fun saveArtists(artists: List<GDriveArtist>) {
        val json = gson.toJson(artists)
        prefs.edit().putString(KEY_ARTISTS, json).apply()
    }

    fun getArtists(): List<GDriveArtist>? {
        val json = prefs.getString(KEY_ARTISTS, null) ?: return null
        return try {
            val type = object : TypeToken<List<GDriveArtist>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            null
        }
    }

    // ── File-based album/track storage ────────────────────────────────

    fun saveArtistAlbums(artistId: String, albums: List<GDriveAlbum>) {
        albumsDir.mkdirs()
        val file = File(albumsDir, "$artistId.json")
        file.writeText(gson.toJson(albums))
    }

    fun getArtistAlbums(artistId: String): List<GDriveAlbum>? {
        val file = File(albumsDir, "$artistId.json")
        if (!file.exists()) return null
        return try {
            val type = object : TypeToken<List<GDriveAlbum>>() {}.type
            gson.fromJson(file.readText(), type)
        } catch (e: Exception) {
            null
        }
    }

    fun saveAlbumTracks(albumId: String, tracks: List<GDriveTrack>) {
        tracksDir.mkdirs()
        val file = File(tracksDir, "$albumId.json")
        file.writeText(gson.toJson(tracks))
    }

    fun getAlbumTracks(albumId: String): List<GDriveTrack>? {
        val file = File(tracksDir, "$albumId.json")
        if (!file.exists()) {
            Log.w(TAG, "Track cache miss: $albumId (file does not exist)")
            return null
        }
        return try {
            val type = object : TypeToken<List<GDriveTrack>>() {}.type
            gson.fromJson(file.readText(), type)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize tracks for $albumId: ${e.message}", e)
            null
        }
    }

    // ── Recently played ─────────────────────────────────────────────

    /**
     * Record an album as recently played. Keeps the last N unique album IDs,
     * most recent first.
     */
    fun recordRecentlyPlayed(albumId: String) {
        val ids = getRecentlyPlayedIds().toMutableList()
        ids.remove(albumId) // Remove if already present (will re-add at front)
        ids.add(0, albumId)
        // Trim to max size
        val trimmed = ids.take(MAX_RECENTLY_PLAYED)
        prefs.edit().putString(KEY_RECENTLY_PLAYED, trimmed.joinToString(",")).apply()
    }

    /**
     * Get recently played album IDs, most recent first.
     */
    fun getRecentlyPlayedIds(): List<String> {
        val raw = prefs.getString(KEY_RECENTLY_PLAYED, null) ?: return emptyList()
        if (raw.isEmpty()) return emptyList()
        return raw.split(",")
    }

    /**
     * Get recently played albums as GDriveAlbum objects, most recent first.
     * Looks up each album ID across all cached artist albums.
     */
    fun getRecentlyPlayedAlbums(): List<GDriveAlbum> {
        val ids = getRecentlyPlayedIds()
        if (ids.isEmpty()) return emptyList()

        // Build album lookup from all cached artists
        val albumById = mutableMapOf<String, GDriveAlbum>()
        val artists = getArtists() ?: return emptyList()
        for (artist in artists) {
            val albums = getArtistAlbums(artist.id) ?: continue
            for (album in albums) {
                albumById[album.id] = album
            }
        }

        // Resolve IDs to albums, preserving order
        return ids.mapNotNull { albumById[it] }
    }

    // ── Cache completeness ────────────────────────────────────────────

    fun hasFullCache(): Boolean {
        return hasCache() && albumsDir.exists() && (albumsDir.listFiles()?.isNotEmpty() == true)
    }

    fun markCacheComplete() {
        prefs.edit().putLong(KEY_LAST_LOADED, System.currentTimeMillis()).apply()
    }

    // ── Library statistics ────────────────────────────────────────────

    data class LibraryStats(
        val artists: Int,
        val albums: Int,
        val tracks: Int,
        val totalCovers: Int,
        val cachedCovers: Int
    )

    fun saveStats(artists: Int, albums: Int, tracks: Int, totalCovers: Int) {
        prefs.edit()
            .putInt(KEY_STAT_ARTISTS, artists)
            .putInt(KEY_STAT_ALBUMS, albums)
            .putInt(KEY_STAT_TRACKS, tracks)
            .putInt(KEY_STAT_TOTAL_COVERS, totalCovers)
            .apply()
    }

    fun getStats(): LibraryStats? {
        if (!prefs.contains(KEY_STAT_ARTISTS)) return null
        return LibraryStats(
            artists = prefs.getInt(KEY_STAT_ARTISTS, 0),
            albums = prefs.getInt(KEY_STAT_ALBUMS, 0),
            tracks = prefs.getInt(KEY_STAT_TRACKS, 0),
            totalCovers = prefs.getInt(KEY_STAT_TOTAL_COVERS, 0),
            cachedCovers = countCachedCovers()
        )
    }

    /** Number of cover image files currently sitting in the album-art cache. */
    fun countCachedCovers(): Int {
        return GDriveImageProvider.cacheDir(context).listFiles()
            ?.count { it.isFile && it.name.endsWith(".img") } ?: 0
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
            .remove(KEY_LAST_LOADED)
            .remove(KEY_STAT_ARTISTS)
            .remove(KEY_STAT_ALBUMS)
            .remove(KEY_STAT_TRACKS)
            .remove(KEY_STAT_TOTAL_COVERS)
            .apply()

        albumsDir.deleteRecursively()
        tracksDir.deleteRecursively()
    }
}
