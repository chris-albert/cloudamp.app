package com.cloudamp.music.models

import com.cloudamp.music.api.DriveFile

/**
 * Represents a saved playback queue that preserves the user's position.
 * Supports both Spotify and Google Drive queues (but not mixed).
 */
data class SavedQueue(
    val id: String,
    val name: String,
    val provider: String, // "spotify" or "gdrive"
    val tracks: List<Track>, // Spotify tracks (empty for gdrive)
    val driveFiles: List<DriveFile>, // GDrive files (empty for spotify)
    val currentIndex: Int,
    val currentPositionMs: Long,
    val createdAt: Long,
    val lastPlayedAt: Long
) {
    companion object {
        const val PROVIDER_SPOTIFY = "spotify"
        const val PROVIDER_GDRIVE = "gdrive"
    }

    fun getTrackCount(): Int {
        return if (provider == PROVIDER_GDRIVE) driveFiles.size else tracks.size
    }

    fun getDisplayProvider(): String {
        return if (provider == PROVIDER_GDRIVE) "Google Drive" else "Spotify"
    }

    fun getCurrentTrackName(): String? {
        return when (provider) {
            PROVIDER_GDRIVE -> {
                if (currentIndex in driveFiles.indices) {
                    driveFiles[currentIndex].name.substringBeforeLast('.')
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
