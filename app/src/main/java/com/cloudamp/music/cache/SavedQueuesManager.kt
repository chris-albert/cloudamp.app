package com.cloudamp.music.cache

import android.content.Context
import android.content.SharedPreferences
import com.cloudamp.music.api.DriveFile
import com.cloudamp.music.models.SavedQueue
import com.cloudamp.music.models.Track
import com.cloudamp.music.playback.GDrivePlaybackManager
import com.cloudamp.music.playback.PlaybackManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

/**
 * Manages saving, loading, renaming, and deleting of playback queues.
 * Persists queues to SharedPreferences using Gson serialization.
 */
class SavedQueuesManager private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val appContext = context.applicationContext

    companion object {
        private const val PREFS_NAME = "saved_queues"
        private const val KEY_QUEUES = "queues"

        @Volatile
        private var instance: SavedQueuesManager? = null

        fun getInstance(context: Context): SavedQueuesManager {
            return instance ?: synchronized(this) {
                instance ?: SavedQueuesManager(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * Get all saved queues, sorted by lastPlayedAt descending (most recent first).
     */
    fun getSavedQueues(): List<SavedQueue> {
        val json = prefs.getString(KEY_QUEUES, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<SavedQueue>>() {}.type
            val queues: List<SavedQueue> = gson.fromJson(json, type)
            queues.sortedByDescending { it.lastPlayedAt }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * Save the current playback queue with the given name.
     * Captures the current state from the active PlaybackManager.
     * @param positionMs explicit position in ms within the current track (used when caller already knows it)
     */
    fun saveCurrentQueue(name: String, positionMs: Long? = null): SavedQueue? {
        val isGDrive = GDrivePlaybackManager.isActiveProvider

        val queue = if (isGDrive) {
            val gdrive = GDrivePlaybackManager.getInstance(appContext)
            val files = gdrive.getQueue()
            if (files.isEmpty()) return null

            SavedQueue(
                id = UUID.randomUUID().toString(),
                name = name,
                provider = SavedQueue.PROVIDER_GDRIVE,
                tracks = emptyList(),
                driveFiles = files,
                currentIndex = gdrive.getCurrentIndex(),
                currentPositionMs = positionMs ?: gdrive.getCurrentPosition(),
                createdAt = System.currentTimeMillis(),
                lastPlayedAt = System.currentTimeMillis()
            )
        } else {
            val playback = PlaybackManager.getInstance(appContext)
            val tracks = playback.getCurrentQueue()
            if (tracks.isEmpty()) return null

            SavedQueue(
                id = UUID.randomUUID().toString(),
                name = name,
                provider = SavedQueue.PROVIDER_SPOTIFY,
                tracks = tracks,
                driveFiles = emptyList(),
                currentIndex = playback.getCurrentIndex(),
                currentPositionMs = positionMs ?: 0,
                createdAt = System.currentTimeMillis(),
                lastPlayedAt = System.currentTimeMillis()
            )
        }

        val queues = getSavedQueues().toMutableList()
        queues.add(queue)
        persistQueues(queues)
        return queue
    }

    /**
     * Update the saved position of an existing queue (auto-save on switch).
     */
    fun updateQueuePosition(queueId: String, currentIndex: Int, currentPositionMs: Long) {
        val queues = getSavedQueues().toMutableList()
        val index = queues.indexOfFirst { it.id == queueId }
        if (index >= 0) {
            queues[index] = queues[index].copy(
                currentIndex = currentIndex,
                currentPositionMs = currentPositionMs,
                lastPlayedAt = System.currentTimeMillis()
            )
            persistQueues(queues)
        }
    }

    /**
     * Rename a saved queue.
     */
    fun renameQueue(queueId: String, newName: String) {
        val queues = getSavedQueues().toMutableList()
        val index = queues.indexOfFirst { it.id == queueId }
        if (index >= 0) {
            queues[index] = queues[index].copy(name = newName)
            persistQueues(queues)
        }
    }

    /**
     * Delete a saved queue.
     */
    fun deleteQueue(queueId: String) {
        val queues = getSavedQueues().toMutableList()
        queues.removeAll { it.id == queueId }
        persistQueues(queues)
    }

    /**
     * Get a single saved queue by ID.
     */
    fun getQueue(queueId: String): SavedQueue? {
        return getSavedQueues().firstOrNull { it.id == queueId }
    }

    private fun persistQueues(queues: List<SavedQueue>) {
        val json = gson.toJson(queues)
        prefs.edit().putString(KEY_QUEUES, json).apply()
    }
}
