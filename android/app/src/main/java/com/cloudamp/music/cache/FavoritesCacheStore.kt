package com.cloudamp.music.cache

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson

/**
 * A toggle whose Drive write hasn't succeeded yet. The latest toggle per
 * album wins. Mirrors PendingToggle in web/src/lib/favorites-cache.ts.
 */
data class PendingToggle(val albumId: String, val favorite: Boolean, val favoritedAt: String)

/** The locally cached favorites list plus any pending (dirty) toggles. */
data class PersistedFavorites(
    val favorites: List<FavoritesCore.FavoriteEntry>,
    val pending: List<PendingToggle>
)

/**
 * Local persistence for [PersistedFavorites] so hearts render at app launch
 * without waiting on Drive. Storage failures degrade to "no cache" — they
 * never throw.
 */
interface FavoritesCacheStore {
    fun load(): PersistedFavorites?
    fun save(persisted: PersistedFavorites)
}

/**
 * SharedPreferences + Gson implementation, same pattern as
 * [GDriveLibraryCache]'s artist list.
 */
class SharedPrefsFavoritesCacheStore(context: Context) : FavoritesCacheStore {

    companion object {
        private const val PREFS_NAME = "favorites_cache"
        private const val KEY_PERSISTED = "persisted"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    override fun load(): PersistedFavorites? {
        val json = prefs.getString(KEY_PERSISTED, null) ?: return null
        return try {
            val parsed = gson.fromJson(json, PersistedFavorites::class.java) ?: return null
            // Gson leaves missing fields null despite the Kotlin types
            @Suppress("SENSELESS_COMPARISON")
            if (parsed.favorites == null || parsed.pending == null) null else parsed
        } catch (e: Exception) {
            null
        }
    }

    override fun save(persisted: PersistedFavorites) {
        try {
            prefs.edit().putString(KEY_PERSISTED, gson.toJson(persisted)).apply()
        } catch (e: Exception) {
            // A failed cache write only costs the next launch its head start.
        }
    }
}
