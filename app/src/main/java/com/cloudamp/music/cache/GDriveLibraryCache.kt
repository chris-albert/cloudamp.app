package com.cloudamp.music.cache

import android.content.Context
import android.content.SharedPreferences
import com.cloudamp.music.api.GDriveAlbum
import com.cloudamp.music.api.GDriveArtist
import com.cloudamp.music.api.GDriveTrack
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
        private const val PREFS_NAME = "gdrive_library_cache"
        private const val KEY_ARTISTS = "cached_artists"
        private const val KEY_LAST_LOADED = "last_loaded_timestamp"
        private const val KEY_ROOT_FOLDER_ID = "gdrive_music_root_id"
        private const val KEY_ROOT_FOLDER_NAME = "gdrive_music_root_name"

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
        if (!file.exists()) return null
        return try {
            val type = object : TypeToken<List<GDriveTrack>>() {}.type
            gson.fromJson(file.readText(), type)
        } catch (e: Exception) {
            null
        }
    }

    // ── Cache completeness ────────────────────────────────────────────

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
            .remove(KEY_LAST_LOADED)
            .apply()

        albumsDir.deleteRecursively()
        tracksDir.deleteRecursively()
    }
}
