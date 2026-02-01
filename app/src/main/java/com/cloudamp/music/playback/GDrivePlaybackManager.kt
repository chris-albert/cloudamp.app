package com.cloudamp.music.playback

import android.content.Context
import android.net.Uri
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import com.cloudamp.music.api.DriveFile
import com.cloudamp.music.api.GoogleDriveApiClient
import com.cloudamp.music.models.Artist
import com.cloudamp.music.models.Track
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.DefaultRenderersFactory
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.audio.AudioCapabilities
import com.google.android.exoplayer2.audio.AudioSink
import com.google.android.exoplayer2.audio.DefaultAudioSink
import com.google.android.exoplayer2.ext.okhttp.OkHttpDataSource
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory
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
        private const val TAG = "GDrivePlayback"

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
    private val spectrumProcessor = SpectrumAudioProcessor()

    private val queue = mutableListOf<DriveFile>()
    private var currentIndex = 0

    fun getQueue(): List<DriveFile> = queue.toList()
    fun getCurrentIndex(): Int = currentIndex

    fun setService(cloudAmpService: CloudAmpService) {
        service = cloudAmpService
    }

    /**
     * Get or create the ExoPlayer instance.
     * Uses OkHttpDataSource with Google Drive auth headers so all media
     * requests include the Bearer token automatically.
     */
    private fun getPlayer(): ExoPlayer {
        if (exoPlayer == null) {
            val dsFactory: DataSource.Factory = getDataSourceFactory()
            val mediaSourceFactory = DefaultMediaSourceFactory(dsFactory)

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build()

            // Custom renderers factory that injects our SpectrumAudioProcessor
            // into the audio pipeline for real-time FFT analysis
            val renderersFactory = object : DefaultRenderersFactory(context) {
                override fun buildAudioSink(
                    context: Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean,
                    enableOffload: Boolean
                ): AudioSink? {
                    return DefaultAudioSink.Builder()
                        .setAudioCapabilities(AudioCapabilities.getCapabilities(context))
                        .setEnableFloatOutput(enableFloatOutput)
                        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                        .setOffloadMode(
                            if (enableOffload) DefaultAudioSink.OFFLOAD_MODE_ENABLED_GAPLESS_REQUIRED
                            else DefaultAudioSink.OFFLOAD_MODE_DISABLED
                        )
                        .setAudioProcessors(arrayOf(spectrumProcessor))
                        .build()
                }
            }

            exoPlayer = ExoPlayer.Builder(context, renderersFactory)
                .setMediaSourceFactory(mediaSourceFactory)
                .setAudioAttributes(audioAttributes, true)
                .build()
                .apply {
                    addListener(playerListener)
                }
        }
        return exoPlayer!!
    }

    /**
     * Get or create the DataSource.Factory with Google Drive auth headers.
     * Uses a streaming-specific HTTP client WITHOUT body-level logging
     * (which would buffer entire audio files into memory).
     */
    private fun getDataSourceFactory(): DataSource.Factory {
        if (dataSourceFactory == null) {
            val driveClient = GoogleDriveApiClient.getInstance(context)
            val hasToken = driveClient.hasAccessToken()
            Log.d(TAG, "Creating DataSource.Factory, hasAccessToken=$hasToken")
            val streamingClient = driveClient.getStreamingHttpClient()
            dataSourceFactory = OkHttpDataSource.Factory(streamingClient)
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
        Log.d(TAG, "playFiles called: ${files.size} files, startIndex=$startIndex")

        // Pre-check: verify we have an access token before attempting playback
        val driveClient = GoogleDriveApiClient.getInstance(context)
        if (!driveClient.hasAccessToken()) {
            Log.e(TAG, "playFiles: NO ACCESS TOKEN! Playback will fail. User needs to re-authenticate.")
        }

        isActiveProvider = true
        queue.clear()
        queue.addAll(files)
        currentIndex = startIndex

        val player = getPlayer()
        Log.d(TAG, "ExoPlayer obtained, state=${player.playbackState}")

        // Clear and set the new playlist.
        // ExoPlayer is configured with our OkHttpDataSource.Factory (via
        // DefaultMediaSourceFactory), so all HTTP requests for these URIs
        // will include the Google Drive Authorization header automatically.
        player.stop()
        player.clearMediaItems()

        files.forEach { file ->
            val uri = buildStreamUri(file.id)
            Log.d(TAG, "Adding media item: ${file.name} -> $uri")
            player.addMediaItem(MediaItem.fromUri(uri))
        }

        player.seekTo(startIndex, 0)
        Log.d(TAG, "Calling prepare()")
        player.prepare()
        player.playWhenReady = true
        Log.d(TAG, "playWhenReady=true, playbackState=${player.playbackState}")

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
     * Get the current 20-band frequency spectrum from the audio processor.
     * Returns null if the player is not initialized.
     */
    fun getSpectrum(): FloatArray = spectrumProcessor.spectrum

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
            // Include actual duration from ExoPlayer when available
            val duration = getDuration()
            val trackWithDuration = if (duration > 0) {
                track.copy(durationMs = duration.toInt())
            } else {
                track
            }
            service?.updateMetadata(trackWithDuration, null)
        }
    }

    private fun updateServiceQueue() {
        val tracks = getQueueAsTracks()
        service?.updateQueue(tracks, currentIndex)
    }

    private val playerListener = object : Player.Listener {

        override fun onPlaybackStateChanged(playbackState: Int) {
            Log.d(TAG, "onPlaybackStateChanged: $playbackState (IDLE=1, BUFFERING=2, READY=3, ENDED=4)")
            when (playbackState) {
                Player.STATE_READY -> {
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
            Log.d(TAG, "onIsPlayingChanged: $isPlaying")
            val state = if (isPlaying) {
                PlaybackStateCompat.STATE_PLAYING
            } else {
                PlaybackStateCompat.STATE_PAUSED
            }
            service?.updatePlaybackState(state, getCurrentPosition())
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "ExoPlayer error: ${error.errorCodeName} (${error.errorCode})", error)
            Log.e(TAG, "  cause: ${error.cause?.message}")
            // Walk the cause chain for more details (e.g., HTTP status codes)
            var cause: Throwable? = error.cause
            var depth = 1
            while (cause != null) {
                Log.e(TAG, "  cause[$depth]: ${cause::class.java.simpleName}: ${cause.message}")
                cause = cause.cause
                depth++
            }
            service?.updatePlaybackState(PlaybackStateCompat.STATE_ERROR)
        }

        override fun onMediaItemTransition(mediaItem: com.google.android.exoplayer2.MediaItem?, reason: Int) {
            val player = exoPlayer ?: return
            currentIndex = player.currentMediaItemIndex
            Log.d(TAG, "onMediaItemTransition: index=$currentIndex, reason=$reason")
            updateServiceMetadata()
            updateServiceQueue()
        }
    }
}
