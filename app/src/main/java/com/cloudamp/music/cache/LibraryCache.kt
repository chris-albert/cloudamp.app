package com.cloudamp.music.cache

import android.content.Context
import android.content.SharedPreferences
import com.cloudamp.music.models.Artist
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.*

class LibraryCache(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private const val PREFS_NAME = "library_cache"
        private const val KEY_ARTISTS = "cached_artists"
        private const val KEY_SAVED_ALBUM_IDS = "cached_saved_album_ids"
        private const val KEY_LAST_LOADED = "last_loaded_timestamp"

        @Volatile
        private var instance: LibraryCache? = null

        fun getInstance(context: Context): LibraryCache {
            return instance ?: synchronized(this) {
                instance ?: LibraryCache(context.applicationContext).also { instance = it }
            }
        }
    }

    fun saveArtists(artists: List<Artist>) {
        val json = gson.toJson(artists)
        prefs.edit().putString(KEY_ARTISTS, json).apply()
        updateLastLoaded()
    }

    fun getArtists(): List<Artist>? {
        val json = prefs.getString(KEY_ARTISTS, null) ?: return null
        return try {
            val type = object : TypeToken<List<Artist>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            null
        }
    }

    fun saveSavedAlbumIds(ids: Set<String>) {
        prefs.edit().putStringSet(KEY_SAVED_ALBUM_IDS, ids).apply()
    }

    fun getSavedAlbumIds(): Set<String> {
        return prefs.getStringSet(KEY_SAVED_ALBUM_IDS, emptySet()) ?: emptySet()
    }

    private fun updateLastLoaded() {
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
            .remove(KEY_SAVED_ALBUM_IDS)
            .remove(KEY_LAST_LOADED)
            .apply()
    }
}
