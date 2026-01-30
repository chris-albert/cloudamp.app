package com.cloudamp.music.playback

import android.content.Context
import android.net.Uri
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import com.cloudamp.music.api.JellyfinApiClient
import com.cloudamp.music.api.JellyfinItem
import com.cloudamp.music.models.Album
import com.cloudamp.music.models.Artist
import com.cloudamp.music.models.Image
import com.cloudamp.music.models.Track
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.ext.okhttp.OkHttpDataSource
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory
import com.google.android.exoplayer2.upstream.DataSource

/**
 * Manages Jellyfin audio playback using ExoPlayer.
 * Maintains its own queue of JellyfinItem objects and streams audio
 * directly from the Jellyfin server via authenticated HTTP requests.
 */
class JellyfinPlaybackManager private constructor(
    private val context: Context
) {

    companion object {
        private const val TAG = "JellyfinPlayback"

        @Volatile
        private var instance: JellyfinPlaybackManager? = null

        fun getInstance(context: Context): JellyfinPlaybackManager {
            return instance ?: synchronized(this) {
                instance ?: JellyfinPlaybackManager(context.applicationContext).also {
                    instance = it
                }
            }
        }

        /** Indicates whether the active provider is currently Jellyfin */
        var isActiveProvider = false
            private set
    }

    private var exoPlayer: ExoPlayer? = null
    private var dataSourceFactory: DataSource.Factory? = null
    private var service: CloudAmpService? = null

    private val queue = mutableListOf<JellyfinItem>()
    private var currentIndex = 0

    // Cache album art URL for the current album
    private var currentAlbumArtUrl: String? = null

    fun getQueue(): List<JellyfinItem> = queue.toList()
    fun getCurrentIndex(): Int = currentIndex

    fun setService(cloudAmpService: CloudAmpService) {
        service = cloudAmpService
    }

    /**
     * Get or create the ExoPlayer instance.
     * Uses OkHttpDataSource with Jellyfin auth headers.
     */
    private fun getPlayer(): ExoPlayer {
        if (exoPlayer == null) {
            val dsFactory: DataSource.Factory = getDataSourceFactory()
            val mediaSourceFactory = DefaultMediaSourceFactory(dsFactory)

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build()

            exoPlayer = ExoPlayer.Builder(context)
                .setMediaSourceFactory(mediaSourceFactory)
                .setAudioAttributes(audioAttributes, true)
                .build()
                .apply {
                    addListener(playerListener)
                }
        }
        return exoPlayer!!
    }

    private fun getDataSourceFactory(): DataSource.Factory {
        if (dataSourceFactory == null) {
            val jellyfinClient = JellyfinApiClient.getInstance(context)
            Log.d(TAG, "Creating DataSource.Factory, hasAccessToken=${jellyfinClient.hasAccessToken()}")
            val streamingClient = jellyfinClient.getStreamingHttpClient()
            dataSourceFactory = OkHttpDataSource.Factory(streamingClient)
        }
        return dataSourceFactory!!
    }

    /**
     * Build a streaming URI for a Jellyfin audio item.
     */
    private fun buildStreamUri(itemId: String): Uri {
        val client = JellyfinApiClient.getInstance(context)
        val url = client.getStreamUrl(itemId)
        Log.d(TAG, "Stream URI: $url")
        return Uri.parse(url ?: "")
    }

    /**
     * Play a list of Jellyfin audio items starting from the given index.
     */
    fun playItems(items: List<JellyfinItem>, startIndex: Int = 0, albumArtUrl: String? = null) {
        Log.d(TAG, "playItems called: ${items.size} items, startIndex=$startIndex")

        val client = JellyfinApiClient.getInstance(context)
        if (!client.hasAccessToken()) {
            Log.e(TAG, "playItems: NO ACCESS TOKEN! Playback will fail.")
        }

        // Deactivate GDrive if switching providers
        if (GDrivePlaybackManager.isActiveProvider) {
            GDrivePlaybackManager.getInstance(context).deactivate()
        }

        isActiveProvider = true
        queue.clear()
        queue.addAll(items)
        currentIndex = startIndex
        currentAlbumArtUrl = albumArtUrl

        val player = getPlayer()
        player.stop()
        player.clearMediaItems()

        items.forEach { item ->
            val uri = buildStreamUri(item.Id)
            Log.d(TAG, "Adding media item: ${item.Name} -> $uri")
            player.addMediaItem(MediaItem.fromUri(uri))
        }

        player.seekTo(startIndex, 0)
        player.prepare()
        player.playWhenReady = true

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
     * Convert a JellyfinItem to a Track model for display in the media session,
     * notifications, and NowPlayingActivity.
     */
    fun jellyfinItemToTrack(item: JellyfinItem): Track {
        val client = JellyfinApiClient.getInstance(context)
        val artistName = item.ArtistItems?.firstOrNull()?.Name
            ?: item.Artists?.firstOrNull()
            ?: item.AlbumArtist
            ?: "Unknown Artist"
        val artistId = item.ArtistItems?.firstOrNull()?.Id ?: "jellyfin"

        val albumImageUrl = if (item.AlbumId != null) {
            client.getImageUrl(item.AlbumId)
        } else if (item.hasPrimaryImage()) {
            client.getImageUrl(item.Id)
        } else {
            currentAlbumArtUrl
        }

        val album = if (item.Album != null) {
            Album(
                id = item.AlbumId ?: item.Id,
                name = item.Album,
                artists = listOf(Artist(id = artistId, name = artistName, uri = "jellyfin:artist:$artistId")),
                images = albumImageUrl?.let { listOf(Image(url = it, height = 300, width = 300)) },
                uri = "jellyfin:album:${item.AlbumId ?: item.Id}"
            )
        } else null

        return Track(
            id = item.Id,
            name = item.Name,
            artists = listOf(Artist(id = artistId, name = artistName, uri = "jellyfin:artist:$artistId")),
            album = album,
            uri = "jellyfin:track:${item.Id}",
            durationMs = item.getRunTimeMs(),
            trackNumber = item.IndexNumber
        )
    }

    fun getQueueAsTracks(): List<Track> {
        return queue.map { jellyfinItemToTrack(it) }
    }

    fun getCurrentPosition(): Long {
        return exoPlayer?.currentPosition ?: 0
    }

    fun getDuration(): Long {
        val duration = exoPlayer?.duration ?: 0
        return if (duration > 0) duration else 0
    }

    fun isPlaying(): Boolean {
        return exoPlayer?.isPlaying ?: false
    }

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
            val item = queue[currentIndex]
            val track = jellyfinItemToTrack(item)
            val duration = getDuration()
            val trackWithDuration = if (duration > 0) {
                track.copy(durationMs = duration.toInt())
            } else {
                track
            }
            val artUrl = track.album?.images?.firstOrNull()?.url
            service?.updateMetadata(trackWithDuration, artUrl)
        }
    }

    private fun updateServiceQueue() {
        val tracks = getQueueAsTracks()
        service?.updateQueue(tracks, currentIndex)
    }

    private val playerListener = object : Player.Listener {

        override fun onPlaybackStateChanged(playbackState: Int) {
            Log.d(TAG, "onPlaybackStateChanged: $playbackState")
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
