package com.cloudamp.music.models

import com.cloudamp.music.api.DriveFile
import com.cloudamp.music.api.JellyfinItem

/**
 * Represents a saved playback queue that preserves the user's position.
 * Supports Google Drive and Jellyfin queues (but not mixed).
 */
data class SavedQueue(
    val id: String,
    val name: String,
    val provider: String, // "gdrive" or "jellyfin"
    val tracks: List<Track> = emptyList(), // Legacy field, kept for serialization compatibility
    val driveFiles: List<DriveFile>, // GDrive files (empty for other providers)
    val jellyfinItems: List<JellyfinItem>? = null, // Jellyfin items (empty for other providers)
    val currentIndex: Int,
    val currentPositionMs: Long,
    val createdAt: Long,
    val lastPlayedAt: Long
) {
    companion object {
        const val PROVIDER_GDRIVE = "gdrive"
        const val PROVIDER_JELLYFIN = "jellyfin"
    }

    fun getTrackCount(): Int {
        return when (provider) {
            PROVIDER_GDRIVE -> driveFiles.size
            PROVIDER_JELLYFIN -> jellyfinItems?.size ?: 0
            else -> 0
        }
    }

    fun getDisplayProvider(): String {
        return when (provider) {
            PROVIDER_GDRIVE -> "Google Drive"
            PROVIDER_JELLYFIN -> "Jellyfin"
            else -> provider
        }
    }

    fun getCurrentTrackName(): String? {
        return when (provider) {
            PROVIDER_GDRIVE -> {
                if (currentIndex in driveFiles.indices) {
                    driveFiles[currentIndex].name.substringBeforeLast('.')
                } else null
            }
            PROVIDER_JELLYFIN -> {
                val items = jellyfinItems ?: emptyList()
                if (currentIndex in items.indices) {
                    items[currentIndex].Name
                } else null
            }
            else -> null
        }
    }
}
