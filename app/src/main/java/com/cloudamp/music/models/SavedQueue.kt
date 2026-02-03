package com.cloudamp.music.models

import com.cloudamp.music.api.DriveFile
import com.cloudamp.music.api.JellyfinItem

/**
 * Represents a saved playback queue that preserves the user's position.
 * Supports Spotify, Google Drive, and Jellyfin queues (but not mixed).
 */
data class SavedQueue(
    val id: String,
    val name: String,
    val provider: String, // "spotify", "gdrive", or "jellyfin"
    val tracks: List<Track>, // Spotify tracks (empty for other providers)
    val driveFiles: List<DriveFile>, // GDrive files (empty for other providers)
    val jellyfinItems: List<JellyfinItem>? = null, // Jellyfin items (empty for other providers)
    val currentIndex: Int,
    val currentPositionMs: Long,
    val createdAt: Long,
    val lastPlayedAt: Long
) {
    companion object {
        const val PROVIDER_SPOTIFY = "spotify"
        const val PROVIDER_GDRIVE = "gdrive"
        const val PROVIDER_JELLYFIN = "jellyfin"
    }

    fun getTrackCount(): Int {
        return when (provider) {
            PROVIDER_GDRIVE -> driveFiles.size
            PROVIDER_JELLYFIN -> jellyfinItems?.size ?: 0
            else -> tracks.size
        }
    }

    fun getDisplayProvider(): String {
        return when (provider) {
            PROVIDER_GDRIVE -> "Google Drive"
            PROVIDER_JELLYFIN -> "Jellyfin"
            else -> "Spotify"
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
            else -> {
                if (currentIndex in tracks.indices) {
                    tracks[currentIndex].name
                } else null
            }
        }
    }
}
