package com.cloudamp.music.cache

import android.content.Context
import android.content.SharedPreferences
import com.cloudamp.music.models.SavedLocation
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Manages saving and deleting of Google Drive folder locations.
 * Persists locations to SharedPreferences using Gson serialization.
 */
class SavedLocationsManager private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private const val PREFS_NAME = "saved_locations"
        private const val KEY_LOCATIONS = "locations"

        @Volatile
        private var instance: SavedLocationsManager? = null

        fun getInstance(context: Context): SavedLocationsManager {
            return instance ?: synchronized(this) {
                instance ?: SavedLocationsManager(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * Get all saved locations, sorted by name.
     */
    fun getSavedLocations(): List<SavedLocation> {
        val json = prefs.getString(KEY_LOCATIONS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<SavedLocation>>() {}.type
            val locations: List<SavedLocation> = gson.fromJson(json, type)
            // Force-access nested fields to surface any ClassCastException
            // from Gson type erasure (e.g. LinkedTreeMap instead of PathSegment)
            for (l in locations) {
                if (l.path.isNotEmpty()) {
                    l.path[0].id
                }
            }
            locations.sortedBy { it.name.lowercase() }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    fun isSaved(folderId: String): Boolean {
        return getSavedLocations().any { it.folderId == folderId }
    }

    /**
     * Save a folder location. The path is the folder chain from the Drive
     * root down to the folder being saved (last entry).
     */
    fun saveLocation(path: List<Pair<String, String>>): SavedLocation? {
        val target = path.lastOrNull() ?: return null
        val location = SavedLocation(
            folderId = target.first,
            name = target.second,
            path = path.map { SavedLocation.PathSegment(it.first, it.second) },
            savedAt = System.currentTimeMillis()
        )
        val locations = getSavedLocations().filter { it.folderId != location.folderId } + location
        persistLocations(locations)
        return location
    }

    fun removeLocation(folderId: String) {
        persistLocations(getSavedLocations().filter { it.folderId != folderId })
    }

    private fun persistLocations(locations: List<SavedLocation>) {
        prefs.edit().putString(KEY_LOCATIONS, gson.toJson(locations)).apply()
    }
}
