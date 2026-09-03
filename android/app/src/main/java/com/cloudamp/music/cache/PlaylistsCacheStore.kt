package com.cloudamp.music.cache

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson

/**
 * The locally cached Playlists File (serialized) plus any ops whose Drive
 * write hasn't succeeded yet, in the order they were applied. Both are
 * stored as JSON strings produced by [PlaylistsCore] so the sealed op
 * type never goes through Gson reflection.
 */
data class PersistedPlaylists(
    val fileJson: String,
    val pendingOps: List<String>
)

/**
 * Local persistence for [PersistedPlaylists] so playlists render at app
 * launch without waiting on Drive. Storage failures degrade to "no cache".
 */
interface PlaylistsCacheStore {
    fun load(): PersistedPlaylists?
    fun save(persisted: PersistedPlaylists)
}

class SharedPrefsPlaylistsCacheStore(context: Context) : PlaylistsCacheStore {

    companion object {
        private const val PREFS_NAME = "playlists_cache"
        private const val KEY_PERSISTED = "persisted"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    override fun load(): PersistedPlaylists? {
        val json = prefs.getString(KEY_PERSISTED, null) ?: return null
        return try {
            val parsed = gson.fromJson(json, PersistedPlaylists::class.java) ?: return null
            // Gson leaves missing fields null despite the Kotlin types
            @Suppress("SENSELESS_COMPARISON")
            if (parsed.fileJson == null || parsed.pendingOps == null) null else parsed
        } catch (e: Exception) {
            null
        }
    }

    override fun save(persisted: PersistedPlaylists) {
        try {
            prefs.edit().putString(KEY_PERSISTED, gson.toJson(persisted)).apply()
        } catch (e: Exception) {
            // A failed cache write only costs the next launch its head start.
        }
    }
}
