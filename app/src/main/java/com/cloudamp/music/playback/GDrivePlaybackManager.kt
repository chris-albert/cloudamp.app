package com.cloudamp.music.playback

import android.content.Context
import android.net.Uri
import android.support.v4.media.session.PlaybackStateCompat
import com.cloudamp.music.api.DriveFile
import com.cloudamp.music.api.GoogleDriveApiClient
import com.cloudamp.music.models.Artist
import com.cloudamp.music.models.Track
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ext.okhttp.OkHttpDataSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.upstream.DataSource

/**
 * Manages Google Drive audio playback using ExoPlayer.
 * Maintains its own queue of DriveFile objects and streams audio
 * directly from Drive via authenticated HTTP requests.
 */
class GDrivePlaybackManager private constructor(
    private val context: Context
) {

    companion object {
        @Volatile
        private var instance: GDrivePlaybackManager? = null

        fun getInstance(context: Context): GDrivePlaybackManager {
            return instance ?: synchronized(this) {
                instance ?: GDrivePlaybackManager(context.applicationContext).also {
                    instance = it
                }
            }
        }

        /** Indicates whether the active provider is currently Google Drive */
        var isActiveProvider = false
            private set
    }

    private var exoPlayer: ExoPlayer? = null
    private var dataSourceFactory: DataSource.Factory? = null
    private var service: CloudAmpService? = null

    private val queue = mutableListOf<DriveFile>()
    private var currentIndex = 0

    fun getQueue(): List<DriveFile> = queue.toList()
    fun getCurrentIndex(): Int = currentIndex

    fun setService(cloudAmpService: CloudAmpService) {
        service = cloudAmpService
    }

    /**
     * Get or create the ExoPlayer instance.
     */
    private fun getPlayer(): ExoPlayer {
        if (exoPlayer == null) {
            exoPlayer = ExoPlayer.Builder(context).build().apply {
                addListener(playerListener)
            }
        }
        return exoPlayer!!
    }

    /**
     * Get or create the DataSource.Factory with Google Drive auth headers.
     */
    private fun getDataSourceFactory(): DataSource.Factory {
        if (dataSourceFactory == null) {
            val driveClient = GoogleDriveApiClient.getInstance(context)
            val okHttpClient = driveClient.getAuthenticatedHttpClient()
            dataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
        }
        return dataSourceFactory!!
    }

    /**
     * Build a streaming URI for a Google Drive file.
     */
    private fun buildStreamUri(fileId: String): Uri {
        return Uri.parse("https://www.googleapis.com/drive/v3/files/$fileId?alt=media")
    }

    /**
     * Play a list of Drive audio files starting from the given index.
     * This sets GDrive as the active provider.
     */
    fun playFiles(files: List<DriveFile>, startIndex: Int = 0) {
        isActiveProvider = true
        queue.clear()
        queue.addAll(files)
        currentIndex = startIndex

        val player = getPlayer()
        val factory = getDataSourceFactory()

        // Build media items for all tracks
        val mediaItems = files.map { file ->
            val uri = buildStreamUri(file.id)
            val mediaSource = ProgressiveMediaSource.Factory(factory)
                .createMediaSource(MediaItem.fromUri(uri))
            mediaSource
        }

        // Clear and set the new playlist
        player.stop()
        player.clearMediaItems()

        files.forEach { file ->
            val uri = buildStreamUri(file.id)
            player.addMediaItem(MediaItem.fromUri(uri))
        }

        player.seekTo(startIndex, 0)
        player.prepare()
        player.play()

        // Update media session metadata and queue
        updateServiceMetadata()
        updateServiceQueue()
        service?.updatePlaybackState(PlaybackStateCompat.STATE_BUFFERING)
    }

    fun play() {
        getPlayer().play()
    }

    fun pause() {
        getPlayer().pause()
    }

    fun stop() {
        getPlayer().stop()
    }

    fun seekTo(positionMs: Long) {
        getPlayer().seekTo(positionMs)
    }

    fun skipToNext() {
        val player = getPlayer()
        if (currentIndex < queue.size - 1) {
            currentIndex++
            player.seekTo(currentIndex, 0)
            updateServiceMetadata()
        }
    }

    fun skipToPrevious() {
        val player = getPlayer()
        if (currentIndex > 0) {
            currentIndex--
            player.seekTo(currentIndex, 0)
            updateServiceMetadata()
        }
    }

    fun skipToQueueItem(index: Int) {
        if (index in queue.indices) {
            currentIndex = index
            getPlayer().seekTo(index, 0)
            updateServiceMetadata()
        }
    }

    /**
     * Convert a DriveFile to a Track model for display in the media session,
     * notifications, and NowPlayingActivity.
     */
    fun driveFileToTrack(file: DriveFile): Track {
        val nameWithoutExtension = file.name.substringBeforeLast('.')
        return Track(
            id = file.id,
            name = nameWithoutExtension,
            artists = listOf(Artist(id = "gdrive", name = "Google Drive", uri = "gdrive:artist:gdrive")),
            album = null,
            uri = "gdrive:file:${file.id}",
            durationMs = 0 // We don't know duration until ExoPlayer reads it
        )
    }

    /**
     * Get the current queue as Track objects for NowPlayingActivity / QueueAdapter.
     */
    fun getQueueAsTracks(): List<Track> {
        return queue.map { driveFileToTrack(it) }
    }

    /**
     * Get the current position in milliseconds from ExoPlayer.
     */
    fun getCurrentPosition(): Long {
        return exoPlayer?.currentPosition ?: 0
    }

    /**
     * Get the current track duration in milliseconds from ExoPlayer.
     */
    fun getDuration(): Long {
        val duration = exoPlayer?.duration ?: 0
        return if (duration > 0) duration else 0
    }

    fun isPlaying(): Boolean {
        return exoPlayer?.isPlaying ?: false
    }

    /**
     * Deactivates GDrive as the provider and releases the player.
     * Called when the user switches back to Spotify.
     */
    fun deactivate() {
        isActiveProvider = false
        exoPlayer?.stop()
    }

    fun release() {
        isActiveProvider = false
        exoPlayer?.release()
        exoPlayer = null
        dataSourceFactory = null
        queue.clear()
        currentIndex = 0
    }

    private fun updateServiceMetadata() {
        if (currentIndex in queue.indices) {
            val file = queue[currentIndex]
            val track = driveFileToTrack(file)
            service?.updateMetadata(track, null)
        }
    }

    private fun updateServiceQueue() {
        val tracks = getQueueAsTracks()
        service?.updateQueue(tracks, currentIndex)
    }

    private val playerListener = object : Player.Listener {

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    // Now we know the real duration — update metadata
                    updateServiceMetadata()
                    val state = if (getPlayer().isPlaying) {
                        PlaybackStateCompat.STATE_PLAYING
                    } else {
                        PlaybackStateCompat.STATE_PAUSED
                    }
                    service?.updatePlaybackState(state, getCurrentPosition())
                }
                Player.STATE_BUFFERING -> {
                    service?.updatePlaybackState(PlaybackStateCompat.STATE_BUFFERING, getCurrentPosition())
                }
                Player.STATE_ENDED -> {
                    service?.updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
                }
                Player.STATE_IDLE -> { }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val state = if (isPlaying) {
                PlaybackStateCompat.STATE_PLAYING
            } else {
                PlaybackStateCompat.STATE_PAUSED
            }
            service?.updatePlaybackState(state, getCurrentPosition())
        }

        override fun onMediaItemTransition(mediaItem: com.google.android.exoplayer2.MediaItem?, reason: Int) {
            // Track changed — update index and metadata
            val player = exoPlayer ?: return
            currentIndex = player.currentMediaItemIndex
            updateServiceMetadata()
            updateServiceQueue()
        }
    }
}
