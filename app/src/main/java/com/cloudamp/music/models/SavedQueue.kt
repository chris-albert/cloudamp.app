package com.cloudamp.music.models

import com.cloudamp.music.api.DriveFile

/**
 * Represents a saved playback queue that preserves the user's position.
 */
data class SavedQueue(
    val id: String,
    val name: String,
    val provider: String = PROVIDER_GDRIVE,
    val driveFiles: List<DriveFile>,
    val currentIndex: Int,
    val currentPositionMs: Long,
    val createdAt: Long,
    val lastPlayedAt: Long
) {
    companion object {
        const val PROVIDER_GDRIVE = "gdrive"
    }

    fun getTrackCount(): Int {
        return driveFiles.size
    }

    fun getDisplayProvider(): String {
        return "Google Drive"
    }

    fun getCurrentTrackName(): String? {
        return if (currentIndex in driveFiles.indices) {
            driveFiles[currentIndex].name.substringBeforeLast('.')
        } else null
    }
}
