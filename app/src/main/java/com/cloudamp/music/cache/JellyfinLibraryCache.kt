package com.cloudamp.music.cache

import android.content.Context
import android.content.SharedPreferences
import com.cloudamp.music.api.JellyfinItem
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.*

class JellyfinLibraryCache(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private const val PREFS_NAME = "jellyfin_library_cache"
        private const val KEY_ARTISTS = "cached_artists"
        private const val KEY_PLAYLISTS = "cached_playlists"
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
        updateLastLoaded()
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
            .remove(KEY_PLAYLISTS)
            .remove(KEY_LAST_LOADED)
            .apply()
    }
}
