package com.cloudamp.music.playback

import com.cloudamp.music.models.Track

/**
 * Common interface for playback providers (Google Drive, Jellyfin).
 *
 * State-query methods are non-suspend (return locally cached values).
 * Transport controls are suspend because some providers need network I/O.
 */
interface PlaybackProvider {
    val providerName: String

    // Queue state (non-suspend, locally cached)
    fun getQueueAsTracks(): List<Track>
    fun getCurrentIndex(): Int
    fun getCurrentPosition(): Long
    fun getDuration(): Long
    fun isPlaying(): Boolean

    // Transport controls
    suspend fun play()
    suspend fun pause()
    suspend fun stop()
    suspend fun seekTo(positionMs: Long)
    suspend fun skipToNext()
    suspend fun skipToPrevious()
    suspend fun skipToQueueItem(index: Int)
}
