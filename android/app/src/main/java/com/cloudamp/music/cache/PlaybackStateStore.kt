package com.cloudamp.music.cache

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.cloudamp.music.api.DriveFile
import com.cloudamp.music.models.SavedQueue
import com.cloudamp.music.playback.ActivePlayback
import com.cloudamp.music.playback.GDrivePlaybackManager
import com.google.gson.Gson

/**
 * Auto-persists the current playback state to disk so it can be restored
 * after the system kills the app (e.g. car turns off, memory pressure).
 *
 * Uses a dedicated SharedPreferences file separate from saved queues.
 * Writes are throttled to at most once every 10 seconds unless forced.
 */
class PlaybackStateStore private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    @Volatile
    private var lastSaveTimestamp: Long = 0

    companion object {
        private const val TAG = "PlaybackStateStore"
        private const val PREFS_NAME = "last_playback_state"
        private const val KEY_STATE = "state"
        private const val THROTTLE_MS = 10_000L
        private const val MAX_AGE_MS = 24 * 60 * 60 * 1000L // 24 hours

        @Volatile
        private var instance: PlaybackStateStore? = null

        fun getInstance(context: Context): PlaybackStateStore {
            return instance ?: synchronized(this) {
                instance ?: PlaybackStateStore(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * Snapshots the current playback state from [ActivePlayback.provider] and
     * writes it to disk. Throttled: skips the write if < 10s since last save
     * unless [force] is true (use force for pause, destroy, track transitions).
     */
    fun save(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastSaveTimestamp < THROTTLE_MS) return

        val active = ActivePlayback.provider ?: return

        val state = when (active) {
            is GDrivePlaybackManager -> {
                val files = active.getQueue()
                if (files.isEmpty()) return
                LastPlaybackState(
                    provider = SavedQueue.PROVIDER_GDRIVE,
                    driveFiles = files,
                    currentIndex = active.getCurrentIndex(),
                    currentPositionMs = active.getCurrentPosition(),
                    wasPlaying = active.isPlaying(),
                    savedAt = now
                )
            }
            else -> return
        }

        try {
            val json = gson.toJson(state)
            prefs.edit().putString(KEY_STATE, json).apply()
            lastSaveTimestamp = now
            Log.d(TAG, "Saved playback state: ${state.provider} index=${state.currentIndex} pos=${state.currentPositionMs}ms")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save playback state: ${e.message}")
        }
    }

    /**
     * Loads the last persisted playback state, or null if none exists or it's stale (>24h).
     */
    fun load(): LastPlaybackState? {
        val json = prefs.getString(KEY_STATE, null) ?: return null
        return try {
            val state = gson.fromJson(json, LastPlaybackState::class.java)
            if (state == null) return null
            // Force-access driveFiles elements to surface any ClassCastException
            // from Gson type erasure (e.g. LinkedTreeMap instead of DriveFile)
            // before the caller tries to use them.
            if (state.driveFiles.isNotEmpty()) {
                state.driveFiles[0].id
            }
            // Skip restore if state is older than 24 hours
            if (System.currentTimeMillis() - state.savedAt > MAX_AGE_MS) {
                Log.d(TAG, "Playback state too old (${(System.currentTimeMillis() - state.savedAt) / 3600000}h), skipping restore")
                clear()
                return null
            }
            state
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load playback state: ${e.message}")
            clear()
            null
        }
    }

    /**
     * Wipes the stored state. Call on explicit stop or queue end to avoid
     * restoring stale state next time.
     */
    fun clear() {
        prefs.edit().remove(KEY_STATE).apply()
        Log.d(TAG, "Cleared playback state")
    }
}

/**
 * Snapshot of the last playback state for auto-restore.
 */
data class LastPlaybackState(
    val provider: String,
    val driveFiles: List<DriveFile>,
    val currentIndex: Int,
    val currentPositionMs: Long,
    val wasPlaying: Boolean,
    val savedAt: Long
)
